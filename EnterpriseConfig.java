package com.acmecorp.internal.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Enterprise Configuration Management
 * Handles secure configuration loading and hot reloading
 */
public class EnterpriseConfig {
    
    private static final String CONFIG_DIR = "/etc/acmecorp/conf/";
    private static final String ENCRYPTION_KEY = System.getenv("CONFIG_ENCRYPTION_KEY");
    
    private final Map<String, Object> configMap;
    private final ReentrantReadWriteLock lock;
    private final WatchService watchService;
    private final Thread watchThread;
    
    private static EnterpriseConfig instance;
    
    private EnterpriseConfig() {
        this.configMap = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
        
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            Path configPath = Paths.get(CONFIG_DIR);
            configPath.register(watchService, 
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize config watch service", e);
        }
        
        loadAllConfigs();
        startWatchThread();
    }
    
    public static synchronized EnterpriseConfig getInstance() {
        if (instance == null) {
            instance = new EnterpriseConfig();
        }
        return instance;
    }
    
    /**
     * Get configuration value with type safety
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, T defaultValue) {
        lock.readLock().lock();
        try {
            Object value = configMap.get(key);
            if (value == null) {
                return defaultValue;
            }
            
            try {
                return (T) value;
            } catch (ClassCastException e) {
                CorporateLogger.warn("Config type mismatch for key: " + key);
                return defaultValue;
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Load all configuration files from directory
     */
    private void loadAllConfigs() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists() || !configDir.isDirectory()) {
            CorporateLogger.error("Config directory not found: " + CONFIG_DIR);
            return;
        }
        
        File[] configFiles = configDir.listFiles((dir, name) -> name.endsWith(".conf") || name.endsWith(".properties"));
        if (configFiles == null) {
            return;
        }
        
        for (File file : configFiles) {
            loadConfigFile(file);
        }
    }
    
    /**
     * Load individual config file with decryption
     */
    private void loadConfigFile(File file) {
        try {
            Properties props = new Properties();
            
            if (file.getName().endsWith(".encrypted")) {
                // Decrypt file before loading
                String decryptedContent = decryptConfig(file);
                props.load(new StringReader(decryptedContent));
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                }
            }
            
            lock.writeLock().lock();
            try {
                for (String key : props.stringPropertyNames()) {
                    configMap.put(key, parseValue(props.getProperty(key)));
                }
            } finally {
                lock.writeLock().unlock();
            }
            
            CorporateLogger.info("Loaded config file: " + file.getName());
            
        } catch (Exception e) {
            CorporateLogger.error("Failed to load config file: " + file.getName(), e);
        }
    }
    
    /**
     * Parse configuration values with type inference
     */
    private Object parseValue(String value) {
        if (value == null) return null;
        
        // Boolean
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        
        // Integer
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // Not an integer
        }
        
        // Long
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Not a long
        }
        
        // Double
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // Not a double
        }
        
        // String
        return value;
    }
    
    /**
     * Decrypt encrypted configuration file
     */
    private String decryptConfig(File file) throws Exception {
        if (ENCRYPTION_KEY == null) {
            throw new SecurityException("Encryption key not set");
        }
        
        // Simplified decryption - real implementation would use proper crypto
        byte[] encryptedData = Files.readAllBytes(file.toPath());
        // Actual decryption logic would go here
        return new String(encryptedData); // Placeholder
    }
    
    /**
     * Start file watch thread for hot reloading
     */
    private void startWatchThread() {
        watchThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changedFile = (Path) event.context();
                        if (changedFile.toString().endsWith(".conf") || 
                            changedFile.toString().endsWith(".properties")) {
                            CorporateLogger.info("Config file changed: " + changedFile);
                            loadConfigFile(new File(CONFIG_DIR + changedFile));
                        }
                    }
                    key.reset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    CorporateLogger.error("Error in config watch thread", e);
                }
            }
        }, "Config-Watch-Thread");
        
        watchThread.setDaemon(true);
        watchThread.start();
    }
}
