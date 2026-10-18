package io.papermc.paper;

import java.util.List;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        SharedConstants.tryDetectVersion();
        // Paper start - Perf: Skip data fixer schema sanity check
        // This is a startup sanity check for entity and block entity types and forces Vanilla datafixer building
        SharedConstants.CHECK_DATA_FIXER_SCHEMA = Boolean.getBoolean("paper.updatingMinecraft");
        // Paper end - Perf: Skip data fixer schema sanity check

        getStartupVersionMessages().forEach(LOGGER::info);

        // OrigCore start - 核心啟動掛鉤
        // 於 Minecraft 主流程之前初始化 OrigCore（設定、CPU 偵測、背景工作池、診斷）。
        // 此呼叫永不阻止伺服器啟動；內部失敗只會停用 OrigCore 的附加功能。
        io.origcore.OrigCoreBootstrap.onServerBoot();
        // OrigCore end

        Main.main(options);
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }
}
