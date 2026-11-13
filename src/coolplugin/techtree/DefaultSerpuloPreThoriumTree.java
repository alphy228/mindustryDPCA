package coolplugin.techtree;

import arc.struct.ObjectSet;
import arc.struct.Seq;
import mindustry.content.*;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.*;
import mindustry.world.Block;

/**
 * Generates the default single-canvas Serpulo pre-Thorium/Plastanium tech tree at {@code config/alphystuff/techtree.hjson}:
 * <ul>
 *   <li>Starts with ONLY {@code dagger} researched at the center.</li>
 *   <li>Other Serpulo T1 units sit to the left (`flare`, `mono`, `risso`) and right (`crawler`, `nova`, `retusa`) of {@code dagger}.</li>
 *   <li>Unit upgrades, unit factories, and {@code additive-reconstructor} go down, left, and right of {@code dagger}.</li>
 *   <li>All other buildings branch UP above {@code dagger} just like vanilla {@code ResearchDialog}.</li>
 *   <li>Strictly vanilla Serpulo pre-Thorium/Plastanium content with vanilla campaign costs (never touches custom {@code dp-*} units).</li>
 * </ul>
 */
public final class DefaultSerpuloPreThoriumTree {

    private DefaultSerpuloPreThoriumTree() {}

    public static TechNodeDef createDefaultRoot() {
        ObjectSet<Item> lateItems = ObjectSet.with(
            Items.thorium, Items.plastanium, Items.phaseFabric, Items.surgeAlloy
        );

        // Center root: ONLY Dagger starts researched
        TechNodeDef dagger = unitNode(UnitTypes.dagger, true, TechNodeDef.DIR_DOWN);

        // === UP FROM DAGGER: All non-unit buildings (like vanilla ResearchDialog) ===
        TechNodeDef duoUp = copySerpuloSubtree(Blocks.duo, lateItems, TechNodeDef.DIR_UP);
        if (duoUp != null) dagger.children.add(duoUp);

        TechNodeDef drillUp = copySerpuloSubtree(Blocks.mechanicalDrill, lateItems, TechNodeDef.DIR_UP);
        if (drillUp != null) dagger.children.add(drillUp);

        dagger.children.add(singleBlockNode(Blocks.coreFoundation, TechNodeDef.DIR_UP));

        TechNodeDef conveyorUp = copySerpuloSubtree(Blocks.conveyor, lateItems, TechNodeDef.DIR_UP);
        if (conveyorUp != null) {
            attachCustomLogisticsAndShops(conveyorUp);
            dagger.children.add(conveyorUp);
        }

        // === LEFT OF DAGGER: Flare, Mono, Risso (with T2/T3 & Air/Naval factories going DOWN) ===
        TechNodeDef flare = unitNode(UnitTypes.flare, false, TechNodeDef.DIR_LEFT);
        flare.children.add(singleBlockNode(Blocks.airFactory, TechNodeDef.DIR_DOWN));
        TechNodeDef horizon = unitNode(UnitTypes.horizon, false, TechNodeDef.DIR_DOWN);
        horizon.children.add(unitNode(UnitTypes.zenith, false, TechNodeDef.DIR_DOWN));
        flare.children.add(horizon);
        dagger.children.add(flare);

        TechNodeDef mono = unitNode(UnitTypes.mono, false, TechNodeDef.DIR_LEFT);
        TechNodeDef poly = unitNode(UnitTypes.poly, false, TechNodeDef.DIR_DOWN);
        poly.children.add(unitNode(UnitTypes.mega, false, TechNodeDef.DIR_DOWN));
        mono.children.add(poly);
        dagger.children.add(mono);

        TechNodeDef risso = unitNode(UnitTypes.risso, false, TechNodeDef.DIR_LEFT);
        risso.children.add(singleBlockNode(Blocks.navalFactory, TechNodeDef.DIR_DOWN));
        TechNodeDef minke = unitNode(UnitTypes.minke, false, TechNodeDef.DIR_DOWN);
        minke.children.add(unitNode(UnitTypes.bryde, false, TechNodeDef.DIR_DOWN));
        risso.children.add(minke);
        dagger.children.add(risso);

        // === DOWN FROM DAGGER: Mace -> Fortress & Ground Factory -> Additive Reconstructor ===
        TechNodeDef mace = unitNode(UnitTypes.mace, false, TechNodeDef.DIR_DOWN);
        mace.children.add(unitNode(UnitTypes.fortress, false, TechNodeDef.DIR_DOWN));
        dagger.children.add(mace);

        TechNodeDef groundFac = singleBlockNode(Blocks.groundFactory, TechNodeDef.DIR_DOWN);
        groundFac.children.add(singleBlockNode(Blocks.additiveReconstructor, TechNodeDef.DIR_DOWN));
        dagger.children.add(groundFac);

        // === RIGHT OF DAGGER: Crawler, Nova, Retusa (with T2/T3 going DOWN) ===
        TechNodeDef crawler = unitNode(UnitTypes.crawler, false, TechNodeDef.DIR_RIGHT);
        TechNodeDef atrax = unitNode(UnitTypes.atrax, false, TechNodeDef.DIR_DOWN);
        atrax.children.add(unitNode(UnitTypes.spiroct, false, TechNodeDef.DIR_DOWN));
        crawler.children.add(atrax);
        dagger.children.add(crawler);

        TechNodeDef nova = unitNode(UnitTypes.nova, false, TechNodeDef.DIR_RIGHT);
        TechNodeDef pulsar = unitNode(UnitTypes.pulsar, false, TechNodeDef.DIR_DOWN);
        pulsar.children.add(unitNode(UnitTypes.quasar, false, TechNodeDef.DIR_DOWN));
        nova.children.add(pulsar);
        dagger.children.add(nova);

        TechNodeDef retusa = unitNode(UnitTypes.retusa, false, TechNodeDef.DIR_RIGHT);
        TechNodeDef oxynoe = unitNode(UnitTypes.oxynoe, false, TechNodeDef.DIR_DOWN);
        oxynoe.children.add(unitNode(UnitTypes.cyerce, false, TechNodeDef.DIR_DOWN));
        retusa.children.add(oxynoe);
        dagger.children.add(retusa);

        assignParents(dagger, null);
        return dagger;
    }

    private static TechNodeDef copySerpuloSubtree(UnlockableContent target, ObjectSet<Item> lateItems, String dir) {
        TechNode src = findSerpuloNode(Planets.serpulo.techTree, target);
        if (src == null) return null;
        return copyFiltered(src, lateItems, dir);
    }

    private static TechNodeDef copyFiltered(TechNode src, ObjectSet<Item> lateItems, String dir) {
        UnlockableContent c = src.content;
        if (c == null || c instanceof SectorPreset) return null;
        if (!isVanillaContent(c)) return null;
        if (c == Blocks.plastaniumCompressor
            || c == Blocks.phaseWeaver
            || c == Blocks.surgeSmelter
            || c == Blocks.thoriumReactor
            || c == Blocks.advancedLaunchPad
            || c == Blocks.landingPad) {
            return null;
        }
        if (c instanceof Item it && lateItems.contains(it)) return null;
        if (usesLateItem(src.requirements, lateItems)) return null;
        if (c instanceof Block b && usesLateItem(b.requirements, lateItems)) return null;

        TechNodeDef def = new TechNodeDef();
        def.id = c.name;
        def.contentType = c.getContentType().name();
        def.contentName = c.name;
        def.direction = dir;
        def.content = c;
        def.costs = cloneStacks(src.requirements);

        for (TechNode child : src.children) {
            TechNodeDef childDef = copyFiltered(child, lateItems, dir);
            if (childDef != null) {
                def.children.add(childDef);
            }
        }
        return def;
    }

    private static boolean isVanillaContent(UnlockableContent c) {
        return c.minfo == null || c.minfo.mod == null;
    }

    private static boolean usesLateItem(ItemStack[] stacks, ObjectSet<Item> lateItems) {
        if (stacks == null) return false;
        for (ItemStack s : stacks) {
            if (s != null && lateItems.contains(s.item)) return true;
        }
        return false;
    }

    private static TechNodeDef unitNode(UnitType u, boolean rootUnlocked, String dir) {
        TechNodeDef def = new TechNodeDef();
        def.id = u.name;
        def.contentType = ContentType.unit.name();
        def.contentName = u.name;
        def.direction = dir;
        def.content = u;
        def.alwaysUnlocked = rootUnlocked;
        if (rootUnlocked) {
            def.costs = ItemStack.empty;
        } else {
            TechNode src = findSerpuloNode(Planets.serpulo.techTree, u);
            def.costs = src != null ? cloneStacks(src.requirements) : cloneStacks(u.researchRequirements());
        }
        return def;
    }

    private static TechNodeDef singleBlockNode(Block b, String dir) {
        TechNodeDef def = new TechNodeDef();
        def.id = b.name;
        def.contentType = ContentType.block.name();
        def.contentName = b.name;
        def.direction = dir;
        def.content = b;
        TechNode src = findSerpuloNode(Planets.serpulo.techTree, b);
        def.costs = src != null ? cloneStacks(src.requirements) : cloneStacks(b.researchRequirements());
        return def;
    }

    private static TechNode findSerpuloNode(TechNode current, UnlockableContent target) {
        if (current == null) return null;
        if (current.content == target) return current;
        for (TechNode ch : current.children) {
            TechNode found = findSerpuloNode(ch, target);
            if (found != null) return found;
        }
        return null;
    }

    private static ItemStack[] cloneStacks(ItemStack[] src) {
        if (src == null || src.length == 0) return ItemStack.empty;
        Seq<ItemStack> out = new Seq<>(src.length);
        for (ItemStack s : src) {
            if (s != null && s.item != null && s.amount > 0) {
                out.add(new ItemStack(s.item, s.amount));
            }
        }
        return out.toArray(ItemStack.class);
    }

    public static void assignParents(TechNodeDef node, TechNodeDef parent) {
        node.parent = parent;
        for (TechNodeDef child : node.children) {
            assignParents(child, node);
        }
    }

    private static void attachCustomLogisticsAndShops(TechNodeDef conveyorSubtree) {
        TechNodeDef router = findById(conveyorSubtree, "router");
        if (router != null) {
            router.children.insert(0, customBlockNode(
                "dp-copper-container",
                TechNodeDef.DIR_UP,
                new ItemStack[]{new ItemStack(Items.copper, 35)}
            ));
            router.children.insert(1, customBlockNode(
                "launch-pad",
                TechNodeDef.DIR_UP,
                new ItemStack[]{
                    new ItemStack(Items.copper, 4000),
                    new ItemStack(Items.lead, 2500),
                    new ItemStack(Items.titanium, 1800),
                    new ItemStack(Items.silicon, 1500)
                }
            ));
        }

        TechNodeDef container = findById(conveyorSubtree, "container");
        if (container != null) {
            TechNodeDef workbench = customBlockNode(
                "dp-workbench",
                TechNodeDef.DIR_UP,
                new ItemStack[]{
                    new ItemStack(Items.copper, 200),
                    new ItemStack(Items.lead, 120),
                    new ItemStack(Items.graphite, 30)
                }
            );
            TechNodeDef bazaar = customBlockNode(
                "dp-bazaar",
                TechNodeDef.DIR_UP,
                new ItemStack[]{
                    new ItemStack(Items.copper, 120),
                    new ItemStack(Items.lead, 1200),
                    new ItemStack(Items.graphite, 250),
                    new ItemStack(Items.titanium, 70),
                    new ItemStack(Items.silicon, 320)
                }
            );
            Seq<ItemStack> altarCosts = new Seq<>();
            for (Item it : mindustry.Vars.content.items()) {
                if (it != null && !it.hidden) {
                    altarCosts.add(new ItemStack(it, 9_999_999));
                }
            }
            TechNodeDef cursedAltar = customBlockNode(
                "dp-cursed-altar",
                TechNodeDef.DIR_UP,
                altarCosts.toArray(ItemStack.class)
            );
            bazaar.children.add(cursedAltar);
            workbench.children.add(bazaar);
            container.children.add(workbench);
        }
    }

    private static TechNodeDef customBlockNode(String id, String dir, ItemStack[] costs) {
        TechNodeDef def = new TechNodeDef();
        def.id = id;
        def.contentType = ContentType.block.name();
        def.contentName = id;
        def.direction = dir;
        def.content = TechTreeConfigIO.resolveContent(ContentType.block.name(), id);
        def.costs = costs != null ? costs : ItemStack.empty;
        return def;
    }

    private static TechNodeDef findById(TechNodeDef current, String id) {
        if (current == null) return null;
        if (id.equals(current.id)) return current;
        for (TechNodeDef ch : current.children) {
            TechNodeDef hit = findById(ch, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
