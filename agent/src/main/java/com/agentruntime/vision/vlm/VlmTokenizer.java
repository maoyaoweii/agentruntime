package com.agentruntime.vision.vlm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SmolVLM / SmolLM2 的 BPE 分词器。
 *
 * <p>为什么自己实现而不引第三方：Android 上没有官方的 tokenizers 绑定，
 * 而这一步在 VLM 链路里是「错了就整条链路输出乱码」的关键点，必须可测、可控。
 *
 * <p>流程与 HuggingFace 一致：
 * <ol>
 *   <li>UTF-8 字节 → ByteLevel 的 unicode 字符（保证任意字节都能映射成可见字符）</li>
 *   <li>GPT-2 正则切分（保留前导空格）</li>
 *   <li>数字单独拆开（配置里的 Digits 预处理器，individual_digits=true）</li>
 *   <li>按 merges 优先级做 BPE 合并</li>
 * </ol>
 */
public final class VlmTokenizer {

    /** GPT-2 预分词正则：前导空格算进词里，这是 BPE 语义的一部分。 */
    private static final Pattern SPLIT = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");

    private final String[] idToToken;
    private final Map<String, Integer> tokenToId;
    private final Map<String, Integer> mergeRanks;
    private final char[] byteToChar;
    private final Map<Character, Integer> charToByte;

    private int unkId = 0;
    private int imageTokenId = 49190;

    private VlmTokenizer(String[] idToToken, Map<String, Integer> tokenToId,
                         Map<String, Integer> mergeRanks) {
        this.idToToken = idToToken;
        this.tokenToId = tokenToId;
        this.mergeRanks = mergeRanks;

        // GPT-2 的 bytes_to_unicode：把 256 个字节映射成不重复的可见字符
        List<Integer> bs = new ArrayList<>(256);
        for (int i = '!'; i <= '~'; i++) bs.add(i);
        for (int i = 0xA1; i <= 0xAC; i++) bs.add(i);
        for (int i = 0xAE; i <= 0xFF; i++) bs.add(i);
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        byteToChar = new char[256];
        charToByte = new HashMap<>(512);
        for (int i = 0; i < bs.size(); i++) {
            int b = bs.get(i);
            char c = (char) (int) cs.get(i);
            byteToChar[b] = c;
            charToByte.put(c, b);
        }
    }

    // ------------------------------------------------------------ 加载

    public static VlmTokenizer fromFiles(File vocabTxt, File mergesTxt) throws IOException {
        List<String> vocabLines = readLines(new FileInputStream(vocabTxt));
        String[] idToToken = new String[vocabLines.size()];
        Map<String, Integer> tokenToId = new HashMap<>(vocabLines.size() * 2);
        for (int i = 0; i < vocabLines.size(); i++) {
            String tok = unescape(vocabLines.get(i));
            idToToken[i] = tok;
            tokenToId.put(tok, i);
        }

        List<String> mergeLines = readLines(new FileInputStream(mergesTxt));
        Map<String, Integer> ranks = new HashMap<>(mergeLines.size() * 2);
        int rank = 0;
        for (String line : mergeLines) {
            if (line.isEmpty()) continue;
            ranks.put(line, rank++);
        }
        return new VlmTokenizer(idToToken, tokenToId, ranks);
    }

    public static VlmTokenizer fromStreams(InputStream vocab, InputStream merges) throws IOException {
        List<String> vocabLines = readLines(vocab);
        String[] idToToken = new String[vocabLines.size()];
        Map<String, Integer> tokenToId = new HashMap<>(vocabLines.size() * 2);
        for (int i = 0; i < vocabLines.size(); i++) {
            String tok = unescape(vocabLines.get(i));
            idToToken[i] = tok;
            tokenToId.put(tok, i);
        }
        List<String> mergeLines = readLines(merges);
        Map<String, Integer> ranks = new HashMap<>(mergeLines.size() * 2);
        int rank = 0;
        for (String line : mergeLines) {
            if (line.isEmpty()) continue;
            ranks.put(line, rank++);
        }
        return new VlmTokenizer(idToToken, tokenToId, ranks);
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    /** vocab.txt 里换行被转义成 \n，读回来要还原。 */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        return s.replace("\\n", "\n").replace("\\r", "\r");
    }

    // ------------------------------------------------------------ 配置

    public void setUnkId(int id) {
        this.unkId = id;
    }

    public void setImageTokenId(int id) {
        this.imageTokenId = id;
    }

    public int imageTokenId() {
        return imageTokenId;
    }

    public int vocabSize() {
        return idToToken.length;
    }

    public int mergeCount() {
        return mergeRanks.size();
    }

    // ------------------------------------------------------------ 编码

    public int[] encode(String text) {
        if (text == null || text.isEmpty()) return new int[0];
        StringBuilder mapped = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            mapped.append(byteToChar[b & 0xFF]);
        }

        List<Integer> ids = new ArrayList<>();
        Matcher m = SPLIT.matcher(mapped);
        while (m.find()) {
            String piece = m.group();
            for (String chunk : splitDigits(piece)) {
                for (String symbol : bpe(chunk)) {
                    Integer id = tokenToId.get(symbol);
                    ids.add(id != null ? id : unkId);
                }
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** 数字逐位拆开（对应 tokenizer 配置里的 individual_digits=true）。 */
    private static List<String> splitDigits(String piece) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (c >= '0' && c <= '9') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(String.valueOf(c));
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 标准 BPE：每次合并优先级最高（rank 最小）的相邻对。 */
    private List<String> bpe(String word) {
        List<String> symbols = new ArrayList<>(word.length());
        for (int i = 0; i < word.length(); i++) {
            symbols.add(String.valueOf(word.charAt(i)));
        }
        while (symbols.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < symbols.size() - 1; i++) {
                Integer r = mergeRanks.get(symbols.get(i) + " " + symbols.get(i + 1));
                if (r != null && r < bestRank) {
                    bestRank = r;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break;
            symbols.set(bestIdx, symbols.get(bestIdx) + symbols.get(bestIdx + 1));
            symbols.remove(bestIdx + 1);
        }
        return symbols;
    }

    // ------------------------------------------------------------ 解码

    public String decode(int[] ids) {
        if (ids == null || ids.length == 0) return "";
        StringBuilder mapped = new StringBuilder();
        for (int id : ids) {
            if (id < 0 || id >= idToToken.length) continue;
            String tok = idToToken[id];
            if (tok == null) continue;
            mapped.append(tok);
        }
        // 反向：unicode 字符 → 字节 → UTF-8
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(mapped.length());
        for (int i = 0; i < mapped.length(); i++) {
            Integer b = charToByte.get(mapped.charAt(i));
            if (b != null) {
                bos.write(b);
            } else {
                byte[] raw = String.valueOf(mapped.charAt(i)).getBytes(StandardCharsets.UTF_8);
                bos.write(raw, 0, raw.length);
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 只解一个 token（调试用）。 */
    public String tokenOf(int id) {
        if (id < 0 || id >= idToToken.length) return null;
        return idToToken[id];
    }

    public Integer idOf(String token) {
        return tokenToId.get(token);
    }
}
