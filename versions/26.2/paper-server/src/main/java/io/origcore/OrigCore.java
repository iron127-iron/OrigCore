package io.origcore;

import io.papermc.paper.util.JarManifests;
import java.util.jar.Manifest;

/**
 * OrigCore 的核心常數與識別資訊。
 *
 * <p>這裡記錄的 Minecraft 版本與 Paper 上游 commit 是 0.1.0 的實際建置基準。
 * 固定上游 commit 是為了讓建置可重現；升級上游時必須同步更新這裡的資訊。</p>
 */
public final class OrigCore {

    /** 核心名稱。 */
    public static final String NAME = "OrigCore";

    /**
     * 建置時若 manifest 沒有 {@code OrigCore-Version} 屬性時的退回值。
     *
     * <p>此值必須與 {@code gradle.properties} 的 {@code origCoreVersion} 保持一致；
     * 正常情況下 {@link #VERSION} 會直接採用 manifest 中的值，因此不會有偏差。</p>
     */
    public static final String FALLBACK_VERSION = "0.1.0";

    /**
     * 核心版本。
     *
     * <p>優先取自 JAR manifest 的 {@code OrigCore-Version} 屬性（由 Gradle 於建置時寫入），
     * 讀取失敗時退回 {@link #FALLBACK_VERSION}。這樣可避免程式碼常數與實際建置版本不一致。</p>
     */
    public static final String VERSION = resolveVersion();

    /** 支援的 Minecraft 版本。0.1.0 只支援此版本。 */
    public static final String MINECRAFT_VERSION = "1.21.11";

    /** 上游專案。 */
    public static final String UPSTREAM_REPOSITORY = "https://github.com/PaperMC/Paper";

    /** 上游分支。 */
    public static final String UPSTREAM_BRANCH = "ver/1.21.11";

    /** 上游 commit（固定，確保建置可重現）。 */
    public static final String UPSTREAM_COMMIT = "c5eb0790f199da6c38d0a650e1e5cd5415b28185";

    /** 工作執行緒名稱前綴。 */
    public static final String WORKER_THREAD_PREFIX = "OrigCore-Worker-";

    /** 設定檔相對路徑。 */
    public static final String CONFIG_PATH = "config/origcore.yml";

    private OrigCore() {
    }

    private static String resolveVersion() {
        try {
            final Manifest manifest = JarManifests.manifest(OrigCore.class);
            if (manifest != null) {
                final String value = manifest.getMainAttributes().getValue("OrigCore-Version");
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        } catch (final RuntimeException ignored) {
            // 讀取 manifest 失敗時退回常數；這不應影響伺服器啟動。
        }
        return FALLBACK_VERSION;
    }
}
