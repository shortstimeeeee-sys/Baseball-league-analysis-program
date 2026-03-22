package com.baseball.domain.game;

import java.text.Normalizer;

/**
 * 승·패·세이브 투수 이름 비교용 정규화 (전각/공백/제로폭 제거).
 */
public final class PitcherNameNormalizer {

    private PitcherNameNormalizer() {
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFKC);
        n = n.replaceAll("[\\s\\u00A0\\u2000-\\u200B\\uFEFF\\u3000]+", "");
        return n;
    }

    public static boolean samePitcher(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = normalize(a);
        String nb = normalize(b);
        return !na.isEmpty() && na.equals(nb);
    }
}
