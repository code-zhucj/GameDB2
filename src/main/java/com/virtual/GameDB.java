package com.virtual;

import com.virtual.exception.GameDBException;
import com.virtual.mbean.GameDBMonitor;
import lombok.Getter;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

public class GameDB {
    @Getter
    private static GameDBConfig config;

    public static void start() {
        config = loadConfig();
        Tables.init();
        GameDBMonitor.register();
    }

    private static GameDBConfig loadConfig() {
        Yaml yaml = new Yaml();
        try (InputStream is = GameDB.class.getClassLoader().getResourceAsStream("GameDBConfig.yaml");) {
            return yaml.loadAs(is, GameDBConfig.class);
        } catch (Exception e) {
            throw new GameDBException("加载GameDB配置异常", e);
        }
    }

    static void main() {
        start();
    }
}
