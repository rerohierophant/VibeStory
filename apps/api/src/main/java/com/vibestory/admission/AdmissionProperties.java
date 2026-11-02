package com.vibestory.admission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vibestory.admission")
public record AdmissionProperties(boolean enabled, String namespace, String textCredentialId, String mediaCredentialId,
                                  double textRate, int textBurst, double mediaRate, int mediaBurst,
                                  Duration permitTtl, Duration errorBackoff, Duration ttlMargin) {
    public AdmissionProperties {
        for (String id : new String[]{namespace,textCredentialId,mediaCredentialId})
            if (id==null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("准入 ID 须为不透明配置标识");
        if (!Double.isFinite(textRate) || !Double.isFinite(mediaRate) || textRate<0.01 || mediaRate<0.01 || textRate>1000 || mediaRate>1000 ||
            textBurst<1 || mediaBurst<1 || textBurst>1000 || mediaBurst>1000 || permitTtl==null || permitTtl.toMillis()<10 || permitTtl.toMillis()>5000 ||
            errorBackoff==null || errorBackoff.toMillis()<100 || errorBackoff.toSeconds()>60 || ttlMargin==null || ttlMargin.toMillis()<1000)
            throw new IllegalArgumentException("生成准入配置无效");
    }
    public Bucket bucket(String type,String provider) {
        boolean text=type.equals("BREAKDOWN");
        String supplier=text?"deepseek":"minimax";
        String identity=(provider.equals("mock")?"mock:":"live:")+supplier+":generation:"+(text?textCredentialId:mediaCredentialId);
        double rate=text?textRate:mediaRate; int burst=text?textBurst:mediaBurst;
        long ttl=(long)Math.ceil(burst/rate*1000)+ttlMargin.toMillis();
        String id=hash(namespace+":"+identity);
        return new Bucket(id,rate,burst,ttl,hash(rate+":"+burst+":"+ttl),namespace+":admission:"+id);
    }
    public record Bucket(String id,double rate,int burst,long ttlMs,String signature,String key) {}
    public static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
