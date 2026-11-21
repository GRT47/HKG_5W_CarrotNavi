import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compatible ECDSA P-256 / SHA-256 mod package signer and verifier matching
 * Open-HMG/HMG-API's Signing.java specification (META-HMGAPI/manifest.json + signature).
 */
public final class HmgModSigner {

    private static final String META_DIR = "META-HMGAPI";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "keygen".equals(args[0])) {
            File priv = new File(args[1]);
            if (priv.exists()) {
                System.out.println("Key exists: " + priv.getAbsolutePath());
                System.out.println("UUID: " + uuid(readPublicKey(priv).getEncoded()));
                return;
            }
            if (priv.getParentFile() != null) priv.getParentFile().mkdirs();
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            writeUtf8(priv, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n");
            writeUtf8(new File(args[1] + ".pub"), Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n");
            System.out.println("Generated key: " + priv.getAbsolutePath());
            System.out.println("UUID: " + uuid(kp.getPublic().getEncoded()));
        } else if (args.length >= 3 && "sign".equals(args[0])) {
            System.out.println(sign(new File(args[1]), new File(args[2])));
        } else if (args.length >= 2 && "verify".equals(args[0])) {
            verify(new File(args[1]));
            System.out.println("HMG-API signature verification PASSED: " + args[1]);
        } else {
            System.err.println("Usage: keygen <privKey> | sign <modDir> <privKey> | verify <modDir>");
            System.exit(2);
        }
    }

    public static String uuid(byte[] pubKeyEncoded) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(pubKeyEncoded);
        h[6] = (byte) ((h[6] & 0x0f) | 0x80);
        h[8] = (byte) ((h[8] & 0x3f) | 0x80);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            sb.append(String.format(Locale.US, "%02x", h[i] & 0xff));
        }
        return sb.toString();
    }

    private static PublicKey readPublicKey(File keyOrPub) throws Exception {
        File pubFile = keyOrPub.getName().endsWith(".pub") ? keyOrPub : new File(keyOrPub.getPath() + ".pub");
        if (!pubFile.isFile()) {
            throw new IOException("Missing public key file: " + pubFile.getAbsolutePath());
        }
        byte[] decoded = Base64.getDecoder().decode(readUtf8(pubFile).trim());
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(decoded));
    }

    private static PrivateKey readPrivateKey(File keyFile) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(readUtf8(keyFile).trim());
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    public static String sign(File modDir, File keyFile) throws Exception {
        File modJson = new File(modDir, "mod.json");
        if (!modJson.isFile()) {
            throw new IOException("Missing mod.json in " + modDir.getAbsolutePath());
        }
        String mj = readUtf8(modJson);
        String id = extractJsonString(mj, "id");
        String version = extractJsonString(mj, "version");
        if (id.isEmpty() || version.isEmpty()) {
            throw new IOException("Invalid id/version in mod.json");
        }

        PrivateKey priv = readPrivateKey(keyFile);
        PublicKey pub = readPublicKey(keyFile);
        String authorUuid = uuid(pub.getEncoded());
        String pubB64 = Base64.getEncoder().encodeToString(pub.getEncoded());

        Map<String, String> files = new LinkedHashMap<String, String>();
        for (String rel : listFiles(modDir)) {
            files.put(rel, sha256Hex(new File(modDir, rel)));
        }

        StringBuilder man = new StringBuilder(1024);
        man.append("{\n");
        man.append("  \"format\": 1,\n");
        man.append("  \"id\": \"").append(escapeJson(id)).append("\",\n");
        man.append("  \"version\": \"").append(escapeJson(version)).append("\",\n");
        man.append("  \"uuid\": \"").append(escapeJson(authorUuid)).append("\",\n");
        man.append("  \"key\": \"").append(escapeJson(pubB64)).append("\",\n");
        man.append("  \"files\": {\n");
        int idx = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (idx++ > 0) man.append(",\n");
            man.append("    \"").append(escapeJson(e.getKey())).append("\": \"").append(e.getValue()).append("\"");
        }
        man.append("\n  }\n");
        man.append("}\n");

        byte[] manifestBytes = man.toString().getBytes(UTF8);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(priv);
        sig.update(manifestBytes);
        String sigB64 = Base64.getEncoder().encodeToString(sig.sign()) + "\n";

        File metaDir = new File(modDir, META_DIR);
        if (!metaDir.exists()) metaDir.mkdirs();
        writeBytes(new File(metaDir, "manifest.json"), manifestBytes);
        writeUtf8(new File(metaDir, "signature"), sigB64);

        return "Signed " + id + " " + version + " (" + files.size() + " files, uuid=" + authorUuid + ")";
    }

    public static void verify(File modDir) throws Exception {
        File mf = new File(modDir, META_DIR + "/manifest.json");
        File sf = new File(modDir, META_DIR + "/signature");
        if (!mf.isFile() || !sf.isFile()) {
            throw new IOException("Missing META-HMGAPI/manifest.json or signature");
        }
        byte[] manifestBytes = readBytes(mf);
        String mfText = new String(manifestBytes, UTF8);

        String id = extractJsonString(mfText, "id");
        String version = extractJsonString(mfText, "version");
        String manUuid = extractJsonString(mfText, "uuid");
        String keyB64 = extractJsonString(mfText, "key");
        byte[] keyBytes = Base64.getDecoder().decode(keyB64);
        String derivedUuid = uuid(keyBytes);
        if (!derivedUuid.equals(manUuid)) {
            throw new IllegalStateException("Manifest UUID mismatch: expected=" + derivedUuid + " actual=" + manUuid);
        }

        PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(pub);
        sig.update(manifestBytes);
        byte[] sigBytes = Base64.getDecoder().decode(readUtf8(sf).trim());
        if (!sig.verify(sigBytes)) {
            throw new IllegalStateException("ECDSA signature verification failed");
        }

        String mj = readUtf8(new File(modDir, "mod.json"));
        if (!id.equals(extractJsonString(mj, "id")) || !version.equals(extractJsonString(mj, "version"))) {
            throw new IllegalStateException("mod.json id/version differ from signed manifest");
        }

        List<String> actualFiles = listFiles(modDir);
        for (String rel : actualFiles) {
            String expectedSha = sha256Hex(new File(modDir, rel));
            String needle = "\"" + rel + "\": \"" + expectedSha + "\"";
            if (!mfText.contains(needle)) {
                throw new IllegalStateException("Manifest hash mismatch or missing file entry: " + rel);
            }
        }
    }

    private static List<String> listFiles(File modDir) {
        List<String> out = new ArrayList<String>();
        walk(modDir, "", out);
        Collections.sort(out);
        return out;
    }

    private static void walk(File dir, String rel, List<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String p = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (p.equals(META_DIR) || p.equals(".disabled")) continue;
            if (f.isDirectory()) walk(f, p, out);
            else out.add(p);
        }
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] data = readBytes(f);
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] readBytes(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String readUtf8(File f) throws IOException {
        return new String(readBytes(f), UTF8);
    }

    private static void writeUtf8(File f, String text) throws IOException {
        writeBytes(f, text.getBytes(UTF8));
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }
}
