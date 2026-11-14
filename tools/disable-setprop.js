// Mindustry v160.7 diagnostic toggle. Load in the CLIENT's JS console to test
// server-only terrain enforcement. Running this on the server does NOT disable
// setprop in connected clients. This affects ALL logic setprop, not only terrain.
//
// Load (adjust path): eval(Core.files.absolute("/path/to/disable-setprop.js").readString())
// Restore: setpropTest.enable()
// Disable again: setpropTest.disable()
// Status: setpropTest.status()
//
// Leaves source code and instruction indices unchanged. Replaces compiled setprop
// with no-ops, including newly compiled processors on the next update. Does NOT
// disable control/set/setrule/etc. Restarting the game also removes this hook.
var setpropTest = (function (previous) {
    if (previous && previous.disable) {
        previous.disable();
        return previous;
    }
    var P = Packages;
    var disabled = false;
    var originals = new P.java.util.WeakHashMap();
    var noOp = new P.mindustry.logic.LExecutor.NoopI();
    var count = 0;
    function scan() {
        if (!disabled) return;
        P.mindustry.gen.Groups.build.each(new P.arc.func.Cons({get: function (build) {
            if (!(build instanceof P.mindustry.world.blocks.logic.LogicBlock.LogicBuild)) return;
            var code = build.executor.instructions;
            if (code == null) return;
            var saved = originals.get(code);
            for (var i = 0; i < code.length; i++) {
                if (code[i] instanceof P.mindustry.logic.LExecutor.SetPropI) {
                    if (saved == null) {
                        saved = new P.java.util.HashMap();
                        originals.put(code, saved);
                    }
                    saved.put(P.java.lang.Integer.valueOf(i), code[i]);
                    code[i] = noOp;
                    count++;
                }
            }
        }}));
    }
    var api = {
        disable: function () {
            disabled = true;
            scan();
            return "setprop disabled in this game instance; other clients are unaffected.";
        },
        enable: function () {
            disabled = false;
            var arrays = originals.entrySet().iterator();
            while (arrays.hasNext()) {
                var entry = arrays.next(), code = entry.getKey();
                var slots = entry.getValue().entrySet().iterator();
                while (slots.hasNext()) {
                    var slot = slots.next(), index = Number(slot.getKey());
                    if (index < code.length && code[index] === noOp) code[index] = slot.getValue();
                }
            }
            originals.clear();
            count = 0;
            return "setprop restored.";
        },
        status: function () {
            return (disabled ? "DISABLED" : "ENABLED") + "; replaced instructions: " + count;
        }
    };
    P.arc.Events.run(P.mindustry.game.EventType.Trigger.update,
        new P.java.lang.Runnable({run: scan}));
    api.disable();
    return api;
})(typeof setpropTest === "undefined" ? null : setpropTest);
setpropTest.status();
