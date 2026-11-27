package net.rwhps.server.plugin.api;

import java.util.*;

/** Immutable statistics values. No native engine object or executable callback crosses this boundary. */
public final class StatisticsRecord {
    private final long sequence;
    private final String matchId, kind;
    private final Map<String,Object> data;
    public StatisticsRecord(long sequence, String matchId, String kind, Map<String,?> data) {
        if (sequence < 1) throw new IllegalArgumentException("sequence");
        this.sequence=sequence; this.matchId=Objects.requireNonNull(matchId); this.kind=Objects.requireNonNull(kind);
        this.data=copyMap(data);
    }
    public long sequence(){return sequence;}
    public String matchId(){return matchId;}
    public String kind(){return kind;}
    public Map<String,Object> data(){return data;}
    private static Map<String,Object> copyMap(Map<String,?> value){
        Map<String,Object> out=new LinkedHashMap<>();value.forEach((k,v)->out.put(Objects.requireNonNull(k),copy(v)));return Collections.unmodifiableMap(out);
    }
    private static Object copy(Object value){
        if(value==null || value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Integer || value instanceof Double || value instanceof Float) return value;
        if(value instanceof List<?>){List<Object> out=new ArrayList<>();for(Object v:(List<?>)value)out.add(copy(v));return Collections.unmodifiableList(out);}
        if(value instanceof Map<?,?>){Map<String,Object> out=new LinkedHashMap<>();((Map<?,?>)value).forEach((k,v)->{if(!(k instanceof String))throw new IllegalArgumentException("String keys required");out.put((String)k,copy(v));});return Collections.unmodifiableMap(out);}
        throw new IllegalArgumentException("Statistics value is not a primitive snapshot: "+value.getClass().getName());
    }
}
