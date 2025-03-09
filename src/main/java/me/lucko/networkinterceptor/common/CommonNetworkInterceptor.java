package me.lucko.networkinterceptor.common;

import java.lang.reflect.Constructor;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.ImmutableList;

import me.lucko.networkinterceptor.InterceptEvent;
import me.lucko.networkinterceptor.blockers.AllowBlocker;
import me.lucko.networkinterceptor.blockers.BlockBlocker;
import me.lucko.networkinterceptor.blockers.Blocker;
import me.lucko.networkinterceptor.blockers.CompositeBlocker;
import me.lucko.networkinterceptor.blockers.LearningBlocker;
import me.lucko.networkinterceptor.blockers.ManualPluginDetectingBlocker;
import me.lucko.networkinterceptor.blockers.PluginAwareBlocker;
import me.lucko.networkinterceptor.bukkit.BukkitPluginOptions;
import me.lucko.networkinterceptor.bungee.BungeePluginOptions;
import me.lucko.networkinterceptor.interceptors.Interceptor;
import me.lucko.networkinterceptor.interceptors.ProxySelectorInterceptor;
import me.lucko.networkinterceptor.interceptors.SecurityManagerInterceptor;
import me.lucko.networkinterceptor.loggers.CompositeLogger;
import me.lucko.networkinterceptor.loggers.ConsoleLogger;
import me.lucko.networkinterceptor.loggers.EventLogger;
import me.lucko.networkinterceptor.loggers.FileLogger;
import me.lucko.networkinterceptor.plugin.TrustedAndBlockedOptions;
import me.lucko.networkinterceptor.plugin.KeepPlugins;
import me.lucko.networkinterceptor.plugin.ManualPluginOptions;
import me.lucko.networkinterceptor.plugin.PluginOptions;
import me.lucko.networkinterceptor.velocity.VelocityNetworkInterceptor;
import me.lucko.networkinterceptor.velocity.VelocityPluginOptions;
import net.md_5.bungee.api.plugin.Plugin;

public class CommonNetworkInterceptor<T extends NetworkInterceptorPlugin<PLUGIN>, PLUGIN> {
    private final T plugin;
    private final Map<InterceptMethod, Interceptor> interceptors = new EnumMap<>(InterceptMethod.class);
    private EventLogger<PLUGIN> logger = null;
    private Blocker<PLUGIN> blocker = null;
    private TrustedAndBlockedOptions<PLUGIN> options = null;
    private boolean isOnStartup = true;
    // private boolean registerManualStopTask = false;

    private boolean ignoreAllowed = false;

    public CommonNetworkInterceptor(T plugin) {
        // init early
        // this is seen as bad practice, but we want to try and catch as
        // many requests as possible
        this.plugin = plugin;

        plugin.saveDefaultConfig();

        enable();

    }

    public void onEnable() {
        if (options != null) { // search now that the plugin is enabled
            options.getTrustedOptions().searchForPlugins(plugin);
            options.getBlockedOptions().searchForPlugins(plugin);
        }
        isOnStartup = false;
    }

    public void onDisable() {
        disable();
    }

    public void reload() {
        plugin.reloadConfig();

        disable();
        try {
            enable();
        } catch (IllegalConfigStateException e) {
            plugin.getLogger().severe(e.getMessage());
            plugin.getLogger().severe("Disabling plugin");
            plugin.disablePlugin();
        }
    }

    public void logAttempt(InterceptEvent<PLUGIN> event) {
        if (this.logger == null) {
            return;
        }

        /*
        if (this.ignoreAllowed && this.blocker instanceof AllowBlocker && !this.blocker.shouldBlock(event)) {
            return;
        }
         */

        this.logger.logAttempt(event);
    }

    public void logBlock(InterceptEvent<PLUGIN> event) {
        if (this.logger == null) {
            return;
        }
        this.logger.logBlock(event);
    }

    public boolean shouldBlock(InterceptEvent<PLUGIN> event) {
        return this.blocker != null && this.blocker.shouldBlock(event);
    }

    public void enable() throws IllegalConfigStateException {
        AbstractConfiguration config = plugin.getConfiguration();

        setupBlockers(config);
        setupLoggers(config);
        setupInterceptors(config);

        for (Interceptor interceptor : this.interceptors.values()) {
            try {
                interceptor.enable();
            } catch (Exception e) {
                if (e instanceof AccessControlException) {
                    if (plugin.getPlatformType() == Platform.BUNGEE) {
                        plugin.getLogger().warning("Since bungee provides its own security manager, "
                                + "the Security Manager Interceptor is unable to be used with a Bungee instance");
                        return;
                    } // else - log the severe stuff below
                }
                plugin.getLogger().log(Level.SEVERE,
                        "Exception occurred whilst enabling " + interceptor.getClass().getName(), e);
            }
        }
    }

    public void disable() {
        for (Interceptor interceptor : this.interceptors.values()) {
            try {
                interceptor.disable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Exception occurred whilst disabling " + interceptor.getClass().getName(), e);
            }
        }
        this.interceptors.clear();
        if (blocker instanceof LearningBlocker) {
            ((LearningBlocker<PLUGIN>) blocker).clear();
        }
    }

    private void setupInterceptors(AbstractConfiguration configuration) {
        List<String> methods = configuration.getStringList("methods");
        if (methods.isEmpty()) {
            plugin.getLogger().info("No methods are defined");
            return;
        }

        Set<InterceptMethod> enabled = EnumSet.noneOf(InterceptMethod.class);
        for (String method : new ArrayList<>(methods)) {
            try {
                enabled.add(InterceptMethod.fromString(method));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("Unknown method: " + method);
                methods.remove(method);
            }
        }

        plugin.getLogger().info("Interceptors: " + methods);

        for (InterceptMethod method : enabled) {
            try {
                Constructor<? extends Interceptor> constructor = method.clazz
                        .getDeclaredConstructor(NetworkInterceptorPlugin.class);
                Interceptor interceptor = constructor.newInstance(plugin);
                this.interceptors.put(method, interceptor);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Exception occurred whilst initialising method " + method, t);
            }
        }
    }

    private void setupLoggers(AbstractConfiguration configuration) {
        if (!configuration.getBoolean("logging.enabled", true)) {
            plugin.getLogger().info("Logging is not enabled");
            return;
        }

        // this option is undocumented
        this.ignoreAllowed = configuration.getBoolean("logging.ignore-allowed", false);

        String mode = configuration.getString("logging.mode", null);
        if (mode == null) {
            plugin.getLogger().severe("Unknown logging mode: " + mode);
            throw new IllegalConfigStateException("logging.mode", mode, "all", "console", "file");
        }
        boolean truncateFile = configuration.getBoolean("logging.truncate-file-on-start", true) && isOnStartup;
        boolean includeTraces = configuration.getBoolean("logging.include-traces", true);
        switch (mode.toLowerCase()) {
            case "all":
                plugin.getLogger().info("Using console+file combined logger");
                this.logger = new CompositeLogger<>(new ConsoleLogger<>(plugin, includeTraces),
                        new FileLogger<>(plugin, truncateFile));
                break;
            case "console":
                plugin.getLogger().info("Using console logger");
                this.logger = new ConsoleLogger<>(plugin, includeTraces);
                break;
            case "file":
                plugin.getLogger().info("Using file logger");
                this.logger = new FileLogger<>(plugin, truncateFile);
                break;
            default:
                plugin.getLogger().severe("Unknown logging mode: " + mode);
                throw new IllegalConfigStateException("logging.mode", mode, "all", "console", "file");
        }
    }

    private void setupBlockers(AbstractConfiguration configuration) {
        if (!configuration.getBoolean("blocking.enabled", false)) {
            plugin.getLogger().info("Blocking is not enabled");
            return;
        }

        List<String> list = ImmutableList.copyOf(configuration.getStringList("targets"));
        options = generatePluginOptions(configuration);
        PluginAwareBlocker<PLUGIN> pluginBlocker = new PluginAwareBlocker<>(options);

        String mode = configuration.getString("mode", null);
        if (mode == null) {
            plugin.getLogger().severe("Unknown mode: " + mode);
            throw new IllegalConfigStateException("mode", mode, "allow", "deny");
        }
        switch (mode.toLowerCase()) {
            case "allow":
                plugin.getLogger().info("Using blocking strategy allow");
                this.blocker = new AllowBlocker<>(list);
                break;
            case "deny":
                plugin.getLogger().info("Using blocking strategy deny");
                this.blocker = new BlockBlocker<>(list);
                break;
            default:
                plugin.getLogger().severe("Unknown mode: " + mode);
                throw new IllegalConfigStateException("mode", mode, "allow", "deny");
        }
        if (this.blocker != null) {
            ManualPluginOptions manOptions = new ManualPluginOptions(null);
            // passing null to the above means the options is empty and the manual blocker
            // is not initialized
            // the below is the correct way to initialize this functiaonlity (in the future)
            // configuration.getConfigurationSection("manual-plugin-assignment"));
            ManualPluginDetectingBlocker<PLUGIN> manBlocker;
            if (manOptions.isEmpty()) { // either disable or empty
                manBlocker = null;
            } else {
                manBlocker = new ManualPluginDetectingBlocker<>(options, manOptions, plugin.getPlatformType());
            }
            this.blocker = new CompositeBlocker<>(manBlocker, pluginBlocker, this.blocker);
            // registerManualStopTask = manBlocker != null &&
            // manOptions.disableAfterStartup();
        }
        if (this.blocker != null && configuration.getBoolean("mapping.enabled", true)) {
            long similarStackTimeoutMs = configuration.getLong("mapping.timer", -1L);
            if (similarStackTimeoutMs < 0) {
                plugin.getLogger().severe("Mapping timer incorrect or not specified");
                throw new IllegalConfigStateException("mapping.timer", configuration.get("mapping.timer", null),
                        "(Need a positive number)");
            }
            plugin.getLogger().info("Using a mapping blocker with timer of " + similarStackTimeoutMs + "ms");
            blocker = new LearningBlocker<>(this.blocker, similarStackTimeoutMs);
        }
    }

    private TrustedAndBlockedOptions<PLUGIN> generatePluginOptions(AbstractConfiguration configuration) {
        // TODO - re-implement in config
        String keepTypeName = configuration.getString("keep-type", "ALL");
        KeepPlugins keepType;
        try {
            keepType = KeepPlugins.valueOf(keepTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown keep type: " + keepTypeName + ". Defaulting to ALL");
            keepType = KeepPlugins.ALL;
        }
        // TODO - re-implement in config (or remove!)
        boolean allowNonPlugin = configuration.getBoolean("keep-non-plugins", false);
        Set<String> trustedPlugins = new HashSet<>(configuration.getStringList("trusted-plugins"));
        PluginOptions<PLUGIN> trustedOpts = getPluginOptions(trustedPlugins, keepType, allowNonPlugin, true);
        Set<String> blockedPlugins = new HashSet<>(configuration.getStringList("blocked-plugins"));
        PluginOptions<PLUGIN> blockedOpts = getPluginOptions(blockedPlugins, keepType, allowNonPlugin, false);
        for (String trustedPluginName : trustedPlugins) {
            if (blockedPlugins.contains(trustedPluginName)) {
                plugin.getLogger().warning(
                        "Conflicting trusted-plugins and blocked-plugins specifications were detected. Outbound connections by "
                                + trustedPluginName + " will be blocked.");
            }
        }
        return new TrustedAndBlockedOptions<PLUGIN>(trustedOpts, blockedOpts);
    }

    @SuppressWarnings("unchecked")
    private PluginOptions<PLUGIN> getPluginOptions(Set<String> plugins, KeepPlugins keepType,
            boolean allowNonPlugin, boolean trust) {
        if (plugin.getPlatformType() == Platform.BUKKIT) {
            return (PluginOptions<PLUGIN>) new BukkitPluginOptions<JavaPlugin>(
                    (JavaPlugin) plugin, keepType, allowNonPlugin, plugins, trust);
        } else if (plugin.getPlatformType() == Platform.BUNGEE) {
            return (PluginOptions<PLUGIN>) new BungeePluginOptions<Plugin>((Plugin) plugin,
                    keepType, allowNonPlugin, plugins, trust);
        } else if (plugin.getPlatformType() == Platform.VELOCITY) {
            return (PluginOptions<PLUGIN>) new VelocityPluginOptions(
                    (VelocityNetworkInterceptor) plugin, keepType, allowNonPlugin, plugins, trust);
        }
        throw new IllegalStateException("Unknown type of plugin: " + plugin);
    }

    public enum InterceptMethod {
        SECURITY_MANAGER("security-manager", SecurityManagerInterceptor.class),
        PROXY_SELECTOR("proxy-selector", ProxySelectorInterceptor.class);

        private final String name;
        private final Class<? extends Interceptor> clazz;

        InterceptMethod(String name, Class<? extends Interceptor> clazz) {
            this.name = name;
            this.clazz = clazz;
        }

        public static InterceptMethod fromString(String string) {
            for (InterceptMethod method : values()) {
                if (method.name.equalsIgnoreCase(string)) {
                    return method;
                }
            }
            throw new IllegalArgumentException();
        }
    }

    public TrustedAndBlockedOptions<PLUGIN> getPluginOptions() {
        return options;
    }

    public Blocker<PLUGIN> getBlocker() {
        return blocker;
    }

    public EventLogger<PLUGIN> getEventLogger() {
        return logger;
    }

    public Map<InterceptMethod, Interceptor> getInterceptors() {
        return new EnumMap<>(interceptors);
    }

    public static class IllegalConfigStateException extends IllegalStateException {

        public IllegalConfigStateException(String path, Object value, Object... options) {
            super(getMessage(path, value, options));
        }

        private static final String getMessage(String path, Object value, Object[] options) {
            return "Illegal config value for '" + path + "': " + value + (options.length == 0 ? ""
                    : (options.length == 1 ? " " + options[0] : " (Available: " + Arrays.asList(options) + ")"));
        }

    }

}
