/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.config;
import net.momirealms.sparrow.yaml.SparrowYaml;
import java.nio.file.Path;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
public record EnergyCoreSettings(int workers,int queueCapacity,boolean diagnosticUi,String displayUnit) {
    public EnergyCoreSettings {
        if(workers<1||workers>64||queueCapacity<1||queueCapacity>1000000) throw new IllegalArgumentException("Invalid async worker or queue limits");
        if(!displayUnit.equals("FE")&&!displayUnit.equals("RF")) throw new IllegalArgumentException("display-unit must be FE or RF");
    }
    public static EnergyCoreSettings load(Path path) throws IOException {
        var document=SparrowYaml.builder().setAllowDuplicateKeys(false).build().load(path);
        Map<?,?> values=document.getValues();
        if(integer(values,"config-version",1)!=1) throw new IllegalArgumentException("Unsupported config version; file preserved");
        Map<?,?> async=section(values,"async"),diagnostics=section(values,"diagnostics");
        return new EnergyCoreSettings(integer(async,"workers",2),integer(async,"queue-capacity",1024),
            bool(diagnostics,"ui",true),string(values,"display-unit","FE"));
    }
    private static Map<?,?> section(Map<?,?> values,String key) {
        if(!values.containsKey(key)) return Map.of();
        if(!(values.get(key) instanceof Map<?,?> section)) throw new IllegalArgumentException(key+" must be a mapping");
        return section;
    }
    private static int integer(Map<?,?> values,String key,int fallback) {
        if(!values.containsKey(key)) return fallback;
        Object value=values.get(key);
        if(!(value instanceof Number)) throw new IllegalArgumentException(key+" must be an integer");
        try { return new BigDecimal(value.toString()).intValueExact(); }
        catch(NumberFormatException|ArithmeticException failure) { throw new IllegalArgumentException(key+" must be an integer in range",failure); }
    }
    private static boolean bool(Map<?,?> values,String key,boolean fallback) {
        if(!values.containsKey(key)) return fallback;
        if(!(values.get(key) instanceof Boolean value)) throw new IllegalArgumentException(key+" must be a boolean");
        return value;
    }
    private static String string(Map<?,?> values,String key,String fallback) {
        if(!values.containsKey(key)) return fallback;
        if(!(values.get(key) instanceof String value)) throw new IllegalArgumentException(key+" must be text");
        return value;
    }
}
