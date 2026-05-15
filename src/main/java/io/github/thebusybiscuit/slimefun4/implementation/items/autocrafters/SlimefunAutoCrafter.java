package io.github.thebusybiscuit.slimefun4.implementation.items.autocrafters;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import io.github.bakedlibs.dough.data.persistent.PersistentDataAPI;
import org.apache.commons.lang.Validate;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.tasks.AsyncRecipeChoiceTask;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.papermc.lib.PaperLib;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import java.util.Map;

/**
 * This extension of the {@link AbstractAutoCrafter} allows you to implement any
 * {@link RecipeType}.
 * The concrete implementation for this can be seen in the {@link EnhancedAutoCrafter} but
 * it theoretically works for any {@link RecipeType}.
 *
 * @author TheBusyBiscuit
 *
 * @see EnhancedAutoCrafter
 *
 */
public class SlimefunAutoCrafter extends AbstractAutoCrafter {

    /**
     * The targeted {@link RecipeType} that is being crafted here.
     */
    private final RecipeType targetRecipeType;

    /**
     * The {@link NamespacedKey} used to store the output material as a fallback.
     */
    private final NamespacedKey recipeOutputMaterialKey;

    @ParametersAreNonnullByDefault
    protected SlimefunAutoCrafter(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe, RecipeType targetRecipeType) {
        super(itemGroup, item, recipeType, recipe);

        this.targetRecipeType = targetRecipeType;
        this.recipeOutputMaterialKey = new NamespacedKey(Slimefun.instance(), "recipe_output_material");
    }

    @Override
    @Nullable
    public AbstractRecipe getSelectedRecipe(@Nonnull Block b) {
        Validate.notNull(b, "The Block cannot be null!");

        BlockState state = PaperLib.getBlockState(b, false).getState();

        if (state instanceof Skull skull) {
            // First, try to get recipe by ID
            String value = PersistentDataAPI.get(skull, recipeStorageKey, PersistentDataType.STRING);
            SlimefunItem item = SlimefunItem.getById(value);

            if (item != null && item.getRecipeType().equals(targetRecipeType)) {
                boolean enabled = !PersistentDataAPI.has(skull, recipeEnabledKey, PersistentDataType.BYTE);
                AbstractRecipe recipe = AbstractRecipe.of(item, targetRecipeType);
                recipe.setEnabled(enabled);
                return recipe;
            }

            // If not found by ID, try to find by stored output material
            SlimefunItem itemByOutput = findItemByStoredMaterial(skull);
            if (itemByOutput != null) {
                boolean enabled = !PersistentDataAPI.has(skull, recipeEnabledKey, PersistentDataType.BYTE);
                AbstractRecipe recipe = AbstractRecipe.of(itemByOutput, targetRecipeType);
                recipe.setEnabled(enabled);
                return recipe;
            }
        }

        return null;
    }

    /**
     * Stores the recipe and its output material in the skull's persistent data.
     * This allows recovery of recipes even if the item ID is lost.
     *
     * @param b
     *            The {@link Block} to store the data on
     * @param recipe
     *            The {@link AbstractRecipe} to select
     */
    @Override
    protected void setSelectedRecipe(@Nonnull Block b, @Nullable AbstractRecipe recipe) {
        super.setSelectedRecipe(b, recipe);

        // Also store the output material as a fallback
        if (recipe != null) {
            BlockState state = PaperLib.getBlockState(b, false).getState();
            if (state instanceof Skull skull) {
                ItemStack output = recipe.getResult();
                // Store the material name as a fallback
                PersistentDataAPI.setString(skull, recipeOutputMaterialKey, output.getType().name());
                state.update(true, false);
            }
        }
    }

    /**
     * Finds a SlimefunItem that produces the stored material output.
     * This is used as a fallback when the item ID is not found or is invalid.
     *
     * @param skull
     *            The {@link Skull} block state to read the stored data from
     * @return The {@link SlimefunItem} matching the stored output material, or null if not found
     */
    @Nullable
    private SlimefunItem findItemByStoredMaterial(@Nonnull Skull skull) {
        // Get the stored material
        String materialName = PersistentDataAPI.get(skull, recipeOutputMaterialKey, PersistentDataType.STRING);
        if (materialName == null) {
            return null;
        }

        Material storedMaterial;
        try {
            storedMaterial = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // Find an item that produces this material
        return findItemByRecipeOutput(new ItemStack(storedMaterial));
    }

    /**
     * Finds a SlimefunItem recipe that produces a specific output ItemStack.
     * Useful for auto crafters that need to search recipes by their result.
     *
     * @param output
     *            The desired output {@link ItemStack} to search for
     * @return The {@link SlimefunItem} that produces this output, or null if not found
     */
    @Nullable
    public SlimefunItem findItemByRecipeOutput(@Nonnull ItemStack output) {
        Validate.notNull(output, "The output ItemStack cannot be null!");

        // Access all registered SlimefunItems
        Map<String, SlimefunItem> itemRegistry = Slimefun.getRegistry().getSlimefunItemIds();

        for (SlimefunItem registeredItem : itemRegistry.values()) {
            // Check if this item uses the target recipe type
            if (registeredItem.getRecipeType().equals(targetRecipeType)) {
                ItemStack recipeOutput = registeredItem.getRecipeOutput();

                if (recipeOutput.getType() == output.getType()) {
                    return registeredItem;
                }
            }
        }

        return null;
    }

    @Override
    protected void updateRecipe(@Nonnull Block b, @Nonnull Player p) {
        ItemStack itemInHand = p.getInventory().getItemInMainHand();
        SlimefunItem item = SlimefunItem.getByItem(itemInHand);

        // Try to get recipe from the SlimefunItem held in hand
        if (item != null && item.getRecipeType().equals(targetRecipeType)) {
            // Fixes #1161
            if (item.canUse(p, true)) {
                AbstractRecipe recipe = AbstractRecipe.of(item, targetRecipeType);

                if (recipe != null) {
                    showRecipeSelectionMenu(p, b, recipe);
                } else {
                    Slimefun.getLocalization().sendMessage(p, "messages.auto-crafting.no-recipes");
                }
            }
        } else {
            // Try to find a recipe by ItemStack output instead
            SlimefunItem itemByOutput = findItemByRecipeOutput(itemInHand);

            if (itemByOutput != null) {
                AbstractRecipe recipe = AbstractRecipe.of(itemByOutput, targetRecipeType);

                if (recipe != null) {
                    setSelectedRecipe(b, recipe);
                    SoundEffect.AUTO_CRAFTER_UPDATE_RECIPE.playAt(b);
                    Slimefun.getLocalization().sendMessage(p, "messages.auto-crafting.recipe-set");
                    showRecipe(p, b, recipe);
                    return;
                }
            }

            Slimefun.getLocalization().sendMessage(p, "messages.auto-crafting.no-recipes");
        }
    }

    /**
     * Shows the recipe selection menu for the player.
     *
     * @param p
     *            The {@link Player} to show the menu to
     * @param b
     *            The {@link Block} of the auto crafter
     * @param recipe
     *            The {@link AbstractRecipe} to show
     */
    @ParametersAreNonnullByDefault
    private void showRecipeSelectionMenu(Player p, Block b, AbstractRecipe recipe) {
        ChestMenu menu = new ChestMenu(getItemName());
        menu.setPlayerInventoryClickable(false);
        menu.setEmptySlotsClickable(false);

        ChestMenuUtils.drawBackground(menu, background);
        ChestMenuUtils.drawBackground(menu, 45, 46, 47, 48, 50, 51, 52, 53);

        menu.addItem(49, CustomItemStack.create(Material.CRAFTING_TABLE, ChatColor.GREEN + Slimefun.getLocalization().getMessage(p, "messages.auto-crafting.select")));
        menu.addMenuClickHandler(49, (pl, stack, slot, action) -> {
            setSelectedRecipe(b, recipe);
            SoundEffect.AUTO_CRAFTER_UPDATE_RECIPE.playAt(b);
            Slimefun.getLocalization().sendMessage(p, "messages.auto-crafting.recipe-set");
            showRecipe(p, b, recipe);
            return false;
        });

        AsyncRecipeChoiceTask task = new AsyncRecipeChoiceTask();
        recipe.show(menu, task);
        menu.open(p);

        SoundEffect.AUTO_CRAFTER_UPDATE_RECIPE.playAt(b);

        if (!task.isEmpty()) {
            task.start(menu.toInventory());
        }
    }
}
