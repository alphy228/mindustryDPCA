package coolplugin;

import arc.files.Fi;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.mod.Plugin;
import coolplugin.techtree.TechResourceStorage;
import coolplugin.techtree.TechTreeCommands;
import coolplugin.techtree.TechTreeService;

/**
 * Main Mindustry server plugin entry point (`config/mods/coolplugin.jar`).
 * All Tech Tree logic, item research costs, HJSON serialization, MenuBuilder UI,
 * and resource storage abstractions live inside {@code coolplugin.techtree}.
 */
public class CoolPlugin extends Plugin {

    private TechTreeService techTree;

    @Override
    public void init() {
        ensureTechTreeCreated();
        techTree.init();
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        ensureTechTreeCreated();
        TechTreeCommands.registerServerCommands(handler, techTree);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        ensureTechTreeCreated();
        TechTreeCommands.registerClientCommands(handler, techTree);
    }

    /** Exposes the {@link TechTreeService} so future plugin subsystems can plug in custom {@link TechResourceStorage}s or conditions. */
    public TechTreeService getTechTree() {
        ensureTechTreeCreated();
        return techTree;
    }

    public void setTechResourceStorage(TechResourceStorage storage) {
        ensureTechTreeCreated();
        techTree.setStorage(storage);
    }

    private void ensureTechTreeCreated() {
        if (techTree == null) {
            Fi configBase = Vars.dataDirectory != null ? Vars.dataDirectory : new Fi("config");
            techTree = new TechTreeService(configBase.child("alphystuff"));
        }
    }
}
