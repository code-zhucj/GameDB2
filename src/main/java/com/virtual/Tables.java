package com.virtual;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Slf4j
public class Tables {

    private static final String MARKER_DIR = "META-INF/tables/";

    // ---- 表注册（供生成代码调用） ----

    private static final Map<Class<?>, TableInfo<?>> registry = new LinkedHashMap<>();

    /** 由生成的代理类在 static 块中调用 */
    @SuppressWarnings("unchecked")
    public static <E extends TableDefine> void register(Class<E> tableClass,
                                                         Class<? extends E> proxyClass,
                                                         Supplier<E> factory) {
        registry.put(tableClass, new TableInfo<>(proxyClass, factory));
    }

    @SuppressWarnings("unchecked")
    private static <E extends TableDefine> TableInfo<E> getTableInfo(Class<E> tableClass) {
        return (TableInfo<E>) registry.get(tableClass);
    }

    private record TableInfo<E extends TableDefine>(Class<? extends E> proxyClass, Supplier<E> factory) {}

    // ---- 公共 API ----

    public static <T extends TableDefine> T create(Class<T> entityClass) {
        TableInfo<T> info = getTableInfo(entityClass);
        if (info == null) {
            throw new GameDBException("未注册的表: " + entityClass.getSimpleName());
        }
        return info.factory().get();
    }

    public static <T extends TableDefine> Supplier<T> creator(Class<T> entityClass) {
        TableInfo<T> info = getTableInfo(entityClass);
        if (info == null) {
            throw new GameDBException("未注册的表: " + entityClass.getSimpleName());
        }
        return info.factory();
    }


    public static Class<? extends TableDefine> getProxyClass(Class<? extends TableDefine> entityClass) {
        TableInfo<?> info = getTableInfo(entityClass);
        if (info == null) {
            throw new GameDBException("未注册的表: " + entityClass.getSimpleName());
        }
        return info.proxyClass();
    }

    public static <T extends TableDefine> Table<T> getTable(Class<T> tableClass) {
        return TablesManager.INSTANCE.getTable(tableClass);
    }

    /** 初始化：扫描 META-INF/tables/ 加载所有代理类，然后初始化所有表 */
    public static void init() {
        loadProxyClasses();
        TablesManager.INSTANCE.initTables();
    }

    // ---- 类路径扫描 ----

    private static void loadProxyClasses() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = cl.getResources(MARKER_DIR);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanFileDir(url);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJarDir(url);
                }
            }
        } catch (IOException e) {
            log.error("扫描 META-INF/tables 失败", e);
        }
    }

    private static void scanFileDir(URL dirUrl) {
        try {
            Path dir = Paths.get(dirUrl.toURI());
            try (var stream = Files.list(dir)) {
                stream.map(Path::getFileName)
                        .map(Path::toString)
                        .forEach(Tables::loadClass);
            }
        } catch (IOException | URISyntaxException e) {
            log.warn("扫描目录失败: {}", dirUrl, e);
        }
    }

    private static void scanJarDir(URL jarUrl) {
        String path = jarUrl.getPath();
        int sep = path.indexOf("!/");
        if (sep < 0) return;
        String jarPath = path.substring(5, sep);
        try (JarFile jar = new JarFile(new File(jarPath))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(MARKER_DIR) && name.length() > MARKER_DIR.length()) {
                    loadClass(name.substring(MARKER_DIR.length()));
                }
            }
        } catch (IOException e) {
            log.warn("扫描 jar 失败: {}", jarUrl, e);
        }
    }

    private static void loadClass(String fqn) {
        try {
            Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            log.warn("无法加载代理类: {}", fqn);
        }
    }

    // ---- 表管理 ----

    private enum TablesManager {
        INSTANCE;
        private final Map<Class<?>, Table<?>> tables = new HashMap<>();

        @SuppressWarnings("unchecked")
        public <T extends TableDefine> Table<T> getTable(Class<T> tableClass) {
            return (Table<T>) tables.get(tableClass);
        }

        @SuppressWarnings("unchecked")
        public void initTables() {
            for (Class<?> tableClass : registry.keySet()) {
                Table<?> table = new Table<>((Class<? extends TableDefine>) tableClass);
                tables.put(tableClass, table);
                table.loadFromDB();
            }
        }
    }
}
