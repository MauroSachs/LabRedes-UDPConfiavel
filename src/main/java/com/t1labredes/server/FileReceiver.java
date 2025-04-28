package com.t1labredes.server;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class FileReceiver {
    private final String fileName;
    private final FileOutputStream outputStream;
    private final Set<Integer> receivedSeqs = new HashSet<>();
    private int expectedSeq = 0;
    private boolean validated = false;


    public FileReceiver(String fileName) throws Exception {
        String clientId = System.getenv("CLIENT_ID");
        this.fileName = "received_" + clientId + "_" + fileName;
        this.outputStream = new FileOutputStream("/app/files/" + this.fileName, false);
    }

    public synchronized boolean writeChunk(int seq, byte[] data) throws Exception {
        if (receivedSeqs.contains(seq)) return false;
        if (seq != expectedSeq) {
            System.err.printf("[FileReceiver] Chunk fora de ordem (esperado %d, recebido %d)%n", expectedSeq, seq);
            return false;
        }
        receivedSeqs.add(seq);
        outputStream.write(data);
        expectedSeq++;
        return true;
    }

    public void close() throws Exception {
        outputStream.close();
    }

    public String calculateHash() throws Exception {
        try (FileInputStream fis = new FileInputStream("/app/files/" + fileName)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    public String getFileName() {
        return fileName;
    }

    public boolean isValidated() {
        return validated;
    }

    public void markValidated() {
        this.validated = true;
    }
}
