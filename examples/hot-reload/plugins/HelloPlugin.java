import dev.cordis.core.Plugin;
import dev.cordis.core.effect.Disposable;
import dev.cordis.core.runtime.Context;

import java.util.Map;

/**
 * Edit the message below while the application runs with --watch.
 */
public class HelloPlugin implements Plugin {
    @Override
    public Object apply(Context ctx, Object config) {
        String message = String.valueOf(((Map<?, ?>) config).get("message"));
        ctx.provide("greeting", message);
        ctx.logger("hello").info(message);
        return (Disposable) () -> ctx.logger("hello").info("Previous greeting unloaded");
    }
}
