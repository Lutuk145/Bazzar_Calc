package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class Main {

    // Config variables
    public static String apiKey = "";
    public static String currentProfileId = "";
    public static String currentMemberUuid = "";

    // Data storage
    public static Map<String, Recipe> savedRecipes = new HashMap<>();
    private static final String RECIPE_FILE = "recipes.ser";
    private static final String CONFIG_FILE = "config.properties";
    private static final Scanner scanner = new Scanner(System.in);
    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) {
        loadConfig();
        loadRecipes();

        // Initial Setup check
        if (apiKey.isEmpty()) {
            System.out.println("--- Initial Setup ---");
            System.out.print("Enter Hypixel API Key: ");
            apiKey = scanner.nextLine().trim();
            saveConfig();
        }

        boolean running = true;
        while (running) {
            System.out.println("\n--- Skyblock Calculator ---");
            System.out.println("1. Add New Recipe");
            System.out.println("2. Calculate from Saved Recipe");
            System.out.println("3. Settings (API Key / Profile)");
            System.out.println("4. Exit");
            System.out.print("Select option: ");

            String input = scanner.nextLine();
            switch (input) {
                case "1":
                    addNewRecipe();
                    break;
                case "2":
                    calculateMode();
                    break;
                case "3":
                    settingsMenu();
                    break;
                case "4":
                    running = false;
                    break;
                default:
                    System.out.println("Invalid option.");
            }
        }

        saveRecipes();
        saveConfig();
    }

    // --- Recipe Logic ---

    private static void addNewRecipe() {
        System.out.print("Enter recipe name: ");
        String name = scanner.nextLine();
        Recipe recipe = new Recipe(name);

        System.out.println("Enter ingredients (type 'end' as ID to finish):");
        while (true) {
            System.out.print("Item ID (e.g., ENCHANTED_DIAMOND): ");
            String id = scanner.nextLine().trim();
            if (id.equalsIgnoreCase("end")) break;

            if (id.isEmpty()) continue;

            System.out.print("Amount: ");
            try {
                int amount = Integer.parseInt(scanner.nextLine().trim());
                recipe.addIngredient(id, amount);
            } catch (NumberFormatException e) {
                System.out.println("Invalid number. Try again.");
            }
        }

        savedRecipes.put(name, recipe);
        saveRecipes();
        System.out.println("Recipe '" + name + "' saved.");
    }

    private static void calculateMode() {
        if (savedRecipes.isEmpty()) {
            System.out.println("No recipes saved.");
            return;
        }

        System.out.println("\nSelect a recipe:");
        List<String> recipeNames = new ArrayList<>(savedRecipes.keySet());
        for (int i = 0; i < recipeNames.size(); i++) {
            System.out.println((i + 1) + ". " + recipeNames.get(i));
        }

        System.out.print("Enter number: ");
        try {
            int choice = Integer.parseInt(scanner.nextLine().trim()) - 1;
            if (choice >= 0 && choice < recipeNames.size()) {
                Recipe selected = savedRecipes.get(recipeNames.get(choice));

                // Ensure we have a valid profile/member selected
                if (currentProfileId.isEmpty() || currentMemberUuid.isEmpty()) {
                    System.out.println("No profile selected. Let's select one now.");
                    selectProfileFlow();
                }

                if (!currentProfileId.isEmpty() && !currentMemberUuid.isEmpty()) {
                    long purse = getPurse(currentProfileId, currentMemberUuid);
                    System.out.println("Current Purse: " + String.format("%,d", purse));

                    double cost = selected.calculatePrice();
                    System.out.println("Recipe Cost: " + String.format("%,.1f", cost));

                    HashMap<String, String> results = selected.fromPurse(purse);
                    System.out.println("--- Can Craft ---");
                    for (Map.Entry<String, String> entry : results.entrySet()) {
                        System.out.println(entry.getKey() + ": " + entry.getValue());
                    }
                }
            } else {
                System.out.println("Invalid selection.");
            }
        } catch (Exception e) {
            System.out.println("Error during calculation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Profile & Member Selection Logic ---

    private static void selectProfileFlow() throws IOException, InterruptedException {
        System.out.print("Enter Minecraft Username to lookup: ");
        String username = scanner.nextLine().trim();

        // 1. Get UUID from Username (Mojang)
        String uuid = getMojangUUID(username);
        if (uuid == null) {
            System.out.println("Could not find UUID for username: " + username);
            return;
        }
        System.out.println("Found UUID: " + uuid);

        // 2. Get Profiles for UUID (Hypixel)
        String url = "https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("API-Key", apiKey)
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("Failed to fetch profiles. Status: " + response.statusCode());
            System.out.println("Response: " + response.body());
            return;
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (root.get("profiles").isJsonNull()) {
            System.out.println("No Skyblock profiles found for this user.");
            return;
        }

        JsonArray profiles = root.getAsJsonArray("profiles");
        System.out.println("\n--- Select Profile ---");
        for (int i = 0; i < profiles.size(); i++) {
            JsonObject p = profiles.get(i).getAsJsonObject();
            String pName = p.get("cute_name").getAsString();
            String pId = p.get("profile_id").getAsString();
            boolean selected = p.get("selected").getAsBoolean();
            System.out.println((i + 1) + ". " + pName + (selected ? " (Active)" : "") + " [ID: " + pId + "]");
        }

        System.out.print("Enter profile number: ");
        int pIndex = Integer.parseInt(scanner.nextLine().trim()) - 1;
        if (pIndex < 0 || pIndex >= profiles.size()) return;

        JsonObject selectedProfile = profiles.get(pIndex).getAsJsonObject();
        currentProfileId = selectedProfile.get("profile_id").getAsString();

        // 3. Select Member from that Profile
        JsonObject members = selectedProfile.getAsJsonObject("members");
        List<String> memberUuids = new ArrayList<>(members.keySet());

        System.out.println("\n--- Select Member ---");
        for (int i = 0; i < memberUuids.size(); i++) {
            // If the member UUID matches the searched user, highlight it
            String mUuid = memberUuids.get(i);
            String label = mUuid.equals(uuid) || mUuid.equals(uuid.replace("-", "")) ? " (You)" : "";
            System.out.println((i + 1) + ". " + mUuid + label);
        }

        System.out.print("Enter member number: ");
        int mIndex = Integer.parseInt(scanner.nextLine().trim()) - 1;
        if (mIndex >= 0 && mIndex < memberUuids.size()) {
            currentMemberUuid = memberUuids.get(mIndex);
            System.out.println("Selected Member: " + currentMemberUuid);
            saveConfig();
        }
    }

    private static String getMojangUUID(String username) {
        try {
            // Using standard Mojang API
            String url = "https://api.mojang.com/users/profiles/minecraft/" + username;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
                return obj.get("id").getAsString();
            }
        } catch (Exception e) {
            System.out.println("Error fetching UUID: " + e.getMessage());
        }
        return null;
    }

    public static long getPurse(String profileId, String memberUuid) throws IOException, InterruptedException {
        String url = "https://api.hypixel.net/v2/skyblock/profile?profile=" + profileId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("API-Key", apiKey)
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("API Error: " + response.statusCode());
            return 0;
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("profile") || root.get("profile").isJsonNull()) return 0;

        JsonObject memberData = root.getAsJsonObject("profile")
                .getAsJsonObject("members")
                .getAsJsonObject(memberUuid);

        if (memberData != null && memberData.has("currencies") && memberData.getAsJsonObject("currencies").has("coin_purse")) {
            return (long) Math.floor(memberData.getAsJsonObject("currencies")
                    .get("coin_purse").getAsDouble());
        } else if (memberData != null && memberData.has("coin_purse")) {
            // Fallback for older API responses or different versions
            return (long) Math.floor(memberData.get("coin_purse").getAsDouble());
        }

        System.out.println("Could not find coin purse for member " + memberUuid);
        return 0;
    }

    public static JsonObject getBazaarPrices() throws IOException, InterruptedException {
        String url = "https://api.hypixel.net/skyblock/bazaar";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch bazaar: " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        return root.get("products").getAsJsonObject();
    }

    private static void settingsMenu() {
        System.out.println("\n--- Settings ---");
        System.out.println("1. Change API Key");
        System.out.println("2. Select New Profile/Member");
        System.out.print("Select: ");
        String choice = scanner.nextLine();

        if (choice.equals("1")) {
            System.out.print("Enter new API Key: ");
            apiKey = scanner.nextLine().trim();
            saveConfig();
        } else if (choice.equals("2")) {
            try {
                selectProfileFlow();
            } catch (Exception e) {
                System.out.println("Error selecting profile: " + e.getMessage());
            }
        }
    }

    // --- File Handling ---

    private static void saveRecipes() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(RECIPE_FILE))) {
            oos.writeObject(savedRecipes);
        } catch (IOException e) {
            System.out.println("Failed to save recipes: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadRecipes() {
        File f = new File(RECIPE_FILE);
        if (f.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
                savedRecipes = (Map<String, Recipe>) ois.readObject();
            } catch (Exception e) {
                System.out.println("Failed to load recipes: " + e.getMessage());
            }
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("apiKey", apiKey != null ? apiKey : "");
        props.setProperty("profileId", currentProfileId != null ? currentProfileId : "");
        props.setProperty("memberUuid", currentMemberUuid != null ? currentMemberUuid : "");
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "Skyblock Calculator Config");
        } catch (IOException e) {
            System.out.println("Failed to save config: " + e.getMessage());
        }
    }

    private static void loadConfig() {
        File f = new File(CONFIG_FILE);
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                Properties props = new Properties();
                props.load(fis);
                apiKey = props.getProperty("apiKey", "");
                currentProfileId = props.getProperty("profileId", "");
                currentMemberUuid = props.getProperty("memberUuid", "");
            } catch (IOException e) {
                System.out.println("Failed to load config.");
            }
        }
    }
}