package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class Recipe implements Serializable {
    private static final long serialVersionUID = 1L; // Recommended for Serializable
    private String name;
    private HashMap<Recipe, Integer> inputs = new HashMap<>();
    private HashMap<String, Integer> inputs2 = new HashMap<>();
    private HashMap<Long, Double> history = new HashMap<>();

    public Recipe(String name) {
        this.name = name;
    }

    // Default constructor for compatibility if needed
    public Recipe() {
        this.name = "Untitled";
    }

    public String getName() {
        return name;
    }

    public double calculatePrice() {
        double sum = 0;
        try {
            Map<String, JsonElement> items = Main.getBazaarPrices().asMap();
            for (String item : inputs2.keySet()) {
                if (items.containsKey(item)) {
                    JsonArray jsonArray = items.get(item).getAsJsonObject().getAsJsonArray("sell_summary");
                    if (jsonArray.size() > 0) {
                        double price = jsonArray.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble() + 0.1;
                        price = Math.round(price * 10) / 10.0;
                        sum += Math.round(price * inputs2.get(item) * 10) / 10.0;
                    } else {
                        System.out.println("Warning: No sell summary for " + item);
                    }
                } else {
                    System.out.println("Warning: Item " + item + " not found in Bazaar");
                }
            }
            for (Recipe recipe : inputs.keySet()) {
                sum += Math.round(recipe.calculatePrice() * inputs.get(recipe) * 10) / 10.0;
            }

        } catch (Exception e) {
            System.out.println("Failed to fetch prices: " + e.getMessage());
        }
        history.put(new Date().getTime(), sum);
        return sum;
    }

    public HashMap<String, String> fromPurse(double purse) {
        HashMap<String, String> output = new HashMap<>();
        double price = calculatePrice();
        if (price == 0) return output; // Avoid division by zero

        long count = (long) Math.floor(purse / price);
        for (String key : inputs2.keySet()) {
            long itemCount = count * inputs2.get(key);
            long fullOrders = itemCount / 71680; // Assuming 71680 is inventory limit or stack limit logic?
            long remainder = itemCount % 71680;
            output.put(key, String.format("%d full orders %d extra", fullOrders, remainder));
        }
        return output;
    }

    public void addIngredient(String item, int quantity) {
        inputs2.put(item, quantity);
    }

    public void addIngredient(Recipe recipe, int quantity) {
        inputs.put(recipe, quantity);
    }

    @Override
    public String toString() {
        return name + " (" + inputs2.size() + " ingredients)";
    }
}