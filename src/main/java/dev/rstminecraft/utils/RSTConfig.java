package dev.rstminecraft.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class RSTConfig {//    模组配置相关函数，模组配置采用.json储存
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configFile;
    private static JsonObject config;


    /**
     * 加载配置文件
     *
     * @param configFile2 配置文件路径
     */
    public static void loadConfig(Path configFile2) {
        configFile = configFile2;
        try {
            if (Files.exists(configFile)) {
                String content = Files.readString(configFile);
                config = GSON.fromJson(content, JsonObject.class);
            } else {
                config = new JsonObject();
                config.addProperty("FirstUse", true);
                config.addProperty("isAutoLog", true);
                config.addProperty("isAutoLogOnSeg1", false);
                config.addProperty("DisplayDebug", false);
                config.addProperty("inspectArmor", true);
                config.addProperty("verboseDisplayDebug", false);
                config.addProperty("FoodIndex", 0);
                config.addProperty("autoLogEnabled",false);
                config.addProperty("HudX",0);
                config.addProperty("HudY",0);
                config.addProperty("enableHud",true);
                saveConfig();
            }
        } catch (IOException e) {
            throw new RuntimeException("无法加载配置: " + configFile, e);
        }
    }

    /**
     * 保存配置文件
     */
    public static void saveConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException("无法保存配置: " + configFile, e);
        }
    }

    /**
     * 读取boolean配置
     *
     * @param key          读取的boolean数据的key
     * @param defaultValue 数据默认值
     * @return 读取结果
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        return config.has(key) ? config.get(key).getAsBoolean() : defaultValue;
    }

    /**
     * 读取String配置
     *
     * @param key          读取的String数据的key
     * @param defaultValue 数据默认值
     * @return 读取结果
     */
    public static String getString(String key, String defaultValue) {
        return config.has(key) ? config.get(key).getAsString() : defaultValue;
    }

    /**
     * 读取int配置
     *
     * @param key          读取的int数据的key
     * @param defaultValue 数据默认值
     * @return 读取结果
     */
    public static int getInt(String key, int defaultValue) {
        return config.has(key) ? config.get(key).getAsInt() : defaultValue;
    }

    /**
     * 写入boolean配置
     *
     * @param key   写入的Boolean数据的key
     * @param value 写入的值
     */
    public static void setBoolean(@NotNull String key, boolean value) {
        config.addProperty(key, value);
        saveConfig();
    }

    /**
     * 写入int配置
     *
     * @param key   写入的int数据的key
     * @param value 写入的值
     */
    public static void setInt(@NotNull String key, int value) {
        config.addProperty(key, value);
        saveConfig();
    }

    /**
     * 写入string配置
     *
     * @param key   写入的String数据的key
     * @param value 写入的值
     */
    public static void setString(@NotNull String key, String value) {
        config.addProperty(key, value);
        saveConfig();
    }
}
