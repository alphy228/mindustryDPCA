package coolplugin.techtree;

import arc.struct.ObjectSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.*;
import mindustry.world.Block;

/**
 * Generates the default placeholder {@code config/alphystuff/techtree.hjson} on first run:
 * <ul>
 *   <li>Starts unlocked with all 7 vanilla Serpulo Tier 1 units (`dagger`, `crawler`, `nova`, `flare`, `mono`, `risso`, `retusa`).</li>
 *   <li>Includes ONLY vanilla Serpulo content before Thorium &amp; Plastanium (never touches custom DPCA units).</li>
 *   <li>Steals exact research item costs directly from vanilla {@link SerpuloTechTree} (`TechNode.requirements`).</li>
 *   <li>Emits an empty {@code conditions: []} array on nodes so special conditions can be added later in HJSON.</li>
 * </ul>
 */
public final class DefaultSerpuloPreThoriumTree {

    private DefaultSerpuloPreThoriumTree() {}

    public static TechNodeDef createDefaultRoot() {
        ObjectSet<Item> lateItems = ObjectSet.with(
            Items.thorium, Items.plastanium, Items.phaseFabric, Items.surgeAlloy
        );

        TechNodeDef root = new TechNodeDef();
        root.id = "serpulo-tier1-root";
        root.contentType = "virtual";
        root.contentName = "serpulo-tier1-root";
        root.displayName = "Serpulo Tier 1 Fleet";
        root.description = "Starting Tier 1 Serpulo units. Branch outward to research units, factories, logistics, production, power, and defenses.";
        root.icon = "units";
        root.virtual = true;
        root.alwaysUnlocked = true;

        // 1. DAGGER (T1 Assault) -> Mace -> Fortress + Turrets/Walls (Duo -> Copper/Scrap/Titanium walls, Scatter/Hail/Salvo/Ripple, Arc/Scorch/Wave/Parallax/Lancer)
        TechNodeDef dagger = unitNode(UnitTypes.dagger, true);
        TechNodeDef mace = unitNode(UnitTypes.mace, false);
        mace.children.add(unitNode(UnitTypes.fortress, false));
        dagger.children.add(mace);
        TechNodeDef duoBranch = copySerpuloSubtree(Blocks.duo, lateItems);
        if (duoBranch != null) dagger.children.add(duoBranch);
        root.children.add(dagger);

        // 2. CRAWLER (T1 Specialist) -> Atrax -> Spiroct + Ground Factory -> Additive Reconstructor
        TechNodeDef crawler = unitNode(UnitTypes.crawler, true);
        TechNodeDef atrax = unitNode(UnitTypes.atrax, false);
        atrax.children.add(unitNode(UnitTypes.spiroct, false));
        crawler.children.add(atrax);

        TechNodeDef groundFac = singleBlockNode(Blocks.groundFactory);
        groundFac.children.add(singleBlockNode(Blocks.additiveReconstructor));
        crawler.children.add(groundFac);
        root.children.add(crawler);

        // 3. NOVA (T1 Support Ground) -> Pulsar -> Quasar + Power/Support (Combustion Gen -> Power Nodes, Batteries, Menders/Force Projector, Steam/Thermal/Differential, Solar) + Core: Foundation
        TechNodeDef nova = unitNode(UnitTypes.nova, true);
        TechNodeDef pulsar = unitNode(UnitTypes.pulsar, false);
        pulsar.children.add(unitNode(UnitTypes.quasar, false));
        nova.children.add(pulsar);

        TechNodeDef combGen = copySerpuloSubtree(Blocks.combustionGenerator, lateItems);
        if (combGen != null) nova.children.add(combGen);
        nova.children.add(singleBlockNode(Blocks.coreFoundation));
        root.children.add(nova);

        // 4. FLARE (T1 Air Fighter) -> Horizon -> Zenith + Air Factory + Item Logistics (Conveyor -> Junction -> Router -> Sorters/Gates/Container/Unloader/Bridge/Titanium Conveyor/Payload Conveyor)
        TechNodeDef flare = unitNode(UnitTypes.flare, true);
        TechNodeDef horizon = unitNode(UnitTypes.horizon, false);
        horizon.children.add(unitNode(UnitTypes.zenith, false));
        flare.children.add(horizon);
        flare.children.add(singleBlockNode(Blocks.airFactory));

        TechNodeDef conveyorBranch = copySerpuloSubtree(Blocks.conveyor, lateItems);
        if (conveyorBranch != null) flare.children.add(conveyorBranch);
        root.children.add(flare);

        // 5. MONO (T1 Mining Drone) -> Poly -> Mega + Drills & Crafting (Mechanical Drill -> Graphite Press -> Pneumatic/Cultivator/Laser/WaterExtractor, Pyratite/Blast Mixer, Silicon Smelter -> Spore/Coal/MultiPress, Kiln -> Pulverizer/Incinerator/Melter/Separator/Cryofluid, MicroProcessor -> Logic)
        TechNodeDef mono = unitNode(UnitTypes.mono, true);
        TechNodeDef poly = unitNode(UnitTypes.poly, false);
        poly.children.add(unitNode(UnitTypes.mega, false));
        mono.children.add(poly);

        TechNodeDef drillBranch = buildDrillAndCraftingBranch(lateItems);
        if (drillBranch != null) mono.children.add(drillBranch);
        root.children.add(mono);

        // 6. RISSO (T1 Naval Assault) -> Minke -> Bryde + Naval Factory
        TechNodeDef risso = unitNode(UnitTypes.risso, true);
        TechNodeDef minke = unitNode(UnitTypes.minke, false);
        minke.children.add(unitNode(UnitTypes.bryde, false));
        risso.children.add(minke);
        risso.children.add(singleBlockNode(Blocks.navalFactory));
        root.children.add(risso);

        // 7. RETUSA (T1 Naval Support) -> Oxynoe -> Cyerce + Liquid Handling (Mechanical Pump -> Conduit -> Liquid Junction/Router/Container/Tank/Bridge/Pulse Conduit/Rotary Pump)
        TechNodeDef retusa = unitNode(UnitTypes.retusa, true);
        TechNodeDef oxynoe = unitNode(UnitTypes.oxynoe, false);
        oxynoe.children.add(unitNode(UnitTypes.cyerce, false));
        retusa.children.add(oxynoe);

        TechNodeDef pumpBranch = copySerpuloSubtree(Blocks.mechanicalPump, lateItems);
        if (pumpBranch != null) retusa.children.add(pumpBranch);
        root.children.add(retusa);

        assignParents(root, null);
        return root;
    }

    private static TechNodeDef buildDrillAndCraftingBranch(ObjectSet<Item> lateItems) {
        // Copy mechanicalDrill subtree EXCEPT mechanicalPump (moved onto Retusa) and combustionGenerator (moved onto Nova)
        TechNodeDef drill = singleBlockNode(Blocks.mechanicalDrill);
        TechNode src = findSerpuloNode(Planets.serpulo.techTree, Blocks.mechanicalDrill);
        if (src != null) {
            for (TechNode ch : src.children) {
                if (ch.content == Blocks.mechanicalPump || ch.content == Blocks.combustionGenerator) {
                    continue;
                }
                TechNodeDef copied = copyFiltered(ch, lateItems);
                if (copied != null) drill.children.add(copied);
            }
        }
        return drill;
    }

    private static TechNodeDef copySerpuloSubtree(UnlockableContent target, ObjectSet<Item> lateItems) {
        TechNode src = findSerpuloNode(Planets.serpulo.techTree, target);
        if (src == null) return null;
        return copyFiltered(src, lateItems);
    }

    private static TechNodeDef copyFiltered(TechNode src, ObjectSet<Item> lateItems) {
        UnlockableContent c = src.content;
        if (c == null || c instanceof SectorPreset) return null;

        // Exclude anything that IS or PRODUCES or COSTS thorium, plastanium, phase-fabric, or surge-alloy
        if (!isVanillaContent(c)) return null;
        if (c == Blocks.plastaniumCompressor || c == Blocks.phaseWeaver || c == Blocks.surgeSmelter || c == Blocks.thoriumReactor) {
            return null;
        }
        if (c instanceof Item it && lateItems.contains(it)) return null;
        if (usesLateItem(src.requirements, lateItems)) return null;
        if (c instanceof Block b && usesLateItem(b.requirements, lateItems)) return null;

        TechNodeDef def = new TechNodeDef();
        def.id = c.name;
        def.contentType = c.getContentType().name();
        def.contentName = c.name;
        def.content = c;
        def.costs = cloneStacks(src.requirements);

        for (TechNode child : src.children) {
            TechNodeDef childDef = copyFiltered(child, lateItems);
            if (childDef != null) {
                def.children.add(childDef);
            }
        }
        return def;
    }

    private static boolean isVanillaContent(UnlockableContent c) {
        // Strictly vanilla content only — never include DataPatch (`dp-*`) or mod content
        return c.minfo == null || c.minfo.mod == null;
    }

    private static boolean usesLateItem(ItemStack[] stacks, ObjectSet<Item> lateItems) {
        if (stacks == null) return false;
        for (ItemStack s : stacks) {
            if (s != null && lateItems.contains(s.item)) return true;
        }
        return false;
    }

    private static TechNodeDef unitNode(UnitType u, boolean rootUnlocked) {
        TechNodeDef def = new TechNodeDef();
        def.id = u.name;
        def.contentType = ContentType.unit.name();
        def.contentName = u.name;
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

    private static TechNodeDef singleBlockNode(Block b) {
        TechNodeDef def = new TechNodeDef();
        def.id = b.name;
        def.contentType = ContentType.block.name();
        def.contentName = b.name;
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
}
