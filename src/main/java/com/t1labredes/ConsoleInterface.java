package com.t1labredes;

import com.t1labredes.device.Device;
import com.t1labredes.protocol.Protocol;
import com.t1labredes.server.Server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.*;
import java.util.Base64;

public class ConsoleInterface implements Runnable {

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Digite um comando (devices, talk <nome> <msg>, sendfile <nome> <arquivo>):");

        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            String[] tokens = input.split(" ");

            if (tokens[0].equalsIgnoreCase("devices")) {
                printActiveDevices();
            } else if (tokens[0].equalsIgnoreCase("talk")) {
                handleTalkCommand(tokens);
            } else if (tokens[0].equalsIgnoreCase("sendfile")) {
                handleSendFileCommand(tokens);
            } else {
                System.out.println("[CLI] Comando desconhecido.");
            }
        }
    }

    private void printActiveDevices() {
        Map<String, Device> devices = Server.getInstance().getActiveDevices();

        if (devices.isEmpty()) {
            System.out.println("[CLI] Nenhum dispositivo ativo encontrado.");
            return;
        }

        System.out.println("[CLI] Dispositivos ativos:");
        devices.forEach((name, dev) -> {
            long diffMs = System.currentTimeMillis() - dev.getLastHeartbeatTime();
            double diffSec = diffMs / 1000.0;
            System.out.printf("  - %s (%s:%d) - último HEARTBEAT há %.2fs\n",
                    name, dev.getIpAddress().getHostAddress(), dev.getPort(), diffSec);
        });
    }

    private void handleTalkCommand(String[] tokens) {
        if (tokens.length < 3) {
            System.out.println("[CLI] Uso: talk <nome> <mensagem>");
            return;
        }

        String targetName = tokens[1];
        String messageText = String.join(" ", Arrays.copyOfRange(tokens, 2, tokens.length));
        String id = String.valueOf(System.nanoTime());

        Device target = Server.getInstance().getActiveDevices().get(targetName);
        if (target == null) {
            System.out.printf("[CLI] Dispositivo \"%s\" não encontrado.%n", targetName);
            return;
        }

        String fullMessage = Protocol.TALK + " " + id + " " + messageText;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            byte[] data = fullMessage.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, target.getIpAddress(), target.getPort());
            boolean ackReceived = false;

            for (int attempt = 1; attempt <= 3; attempt++) {
                socket.send(packet);
                System.out.printf("[CLI] TALK enviado a %s (tentativa %d)%n", targetName, attempt);

                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String responseMessage = new String(response.getData(), 0, response.getLength());

                    if (responseMessage.equals(Protocol.ACK + " " + id)) {
                        System.out.printf("[CLI] ACK recebido de %s!%n", targetName);
                        ackReceived = true;
                        break;
                    }
                    else
                        System.out.println("[CLI] Mensagem desconhecida");
                } catch (SocketTimeoutException e) {
                    System.out.println("[CLI] Timeout aguardando ACK...");
                }
            }

            if (!ackReceived) {
                System.out.println("[CLI] Falha ao receber ACK após 3 tentativas.");
            }
        } catch (Exception e) {
            System.err.printf("[CLI] Erro ao enviar TALK: %s%n", e.getMessage());
        }
    }

    private void handleSendFileCommand(String[] tokens) {
        if (tokens.length < 3) {
            System.out.println("[CLI] Uso: sendfile <nome> <arquivo>");
            return;
        }

        String targetName = tokens[1];
        String fileName = tokens[2];

        Device target = Server.getInstance().getActiveDevices().get(targetName);
        if (target == null) {
            System.out.printf("[CLI] Dispositivo \"%s\" não encontrado.%n", targetName);
            return;
        }

        File file = new File("files/" + fileName);
        if (!file.exists()) {
            System.out.printf("[CLI] Arquivo \"%s\" não encontrado.%n", fileName);
            return;
        }

        long fileSize = file.length();
        String id = String.valueOf(System.nanoTime());
        String fileMsg = Protocol.FILE + " " + id + " " + file.getName() + " " + fileSize;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            byte[] sendData = fileMsg.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, target.getIpAddress(), target.getPort());

            boolean ackReceived = false;
            for (int attempt = 1; attempt <= 5; attempt++) {
                socket.send(sendPacket);
                System.out.printf("[CLI] FILE enviado (tentativa %d)%n", attempt);
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String ackMsg = new String(response.getData(), 0, response.getLength());

                    if (ackMsg.equals(Protocol.ACK + " " + id)) {
                        System.out.println("[CLI] ACK recebido! Iniciando envio de blocos...");
                        ackReceived = true;
                        break;
                    }
                    else
                        System.out.println("[CLI] Mensagem desconhecida");

                } catch (SocketTimeoutException e) {
                    System.out.println("[CLI] Timeout aguardando ACK...");
                    if (attempt >= 4) Thread.sleep((attempt - 3) * 500L);
                }
            }

            if (!ackReceived) {
                System.out.println("[CLI] Falha ao iniciar envio de arquivo.");
                return;
            }

            sendChunks(file, id, target);
        } catch (Exception e) {
            System.err.printf("[CLI] Erro ao enviar FILE: %s%n", e.getMessage());
        }
    }

    private void sendChunks(File file, String id, Device target) {
        try (FileInputStream fis = new FileInputStream(file);
             DatagramSocket socket = new DatagramSocket()) {

            socket.setSoTimeout(2000);
            if (!sendChunksLoop(fis, socket, id, target)) return;

            System.out.println("[CLI] Todos os CHUNKs foram enviados com sucesso!");
            sendEndMessage(file, socket, id, target);

        } catch (Exception e) {
            System.err.printf("[CLI] Erro durante envio dos CHUNKs: %s%n", e.getMessage());
        }
    }

    private boolean sendChunksLoop(FileInputStream fis, DatagramSocket socket, String id, Device target) throws Exception {
        final int MAX_PACKET_SIZE = 1024;
        int sequence = 0;
        long totalSentBytes = 0;
        long totalFileSize = fis.getChannel().size();

        while (true) {
            String header = Protocol.CHUNK + " " + id + " " + sequence + " ";
            byte[] headerBytes = header.getBytes();

            int maxPayloadSize = MAX_PACKET_SIZE - headerBytes.length;
            int maxRawSize = (int) Math.floor(maxPayloadSize * 0.75);

            byte[] rawBuffer = new byte[maxRawSize];
            int bytesRead = fis.read(rawBuffer);
            if (bytesRead == -1) break;

            byte[] chunkData = Arrays.copyOf(rawBuffer, bytesRead);
            boolean sent = sendChunk(socket, target, headerBytes, chunkData, id, sequence, bytesRead, totalFileSize);

            if (!sent) {
                System.err.printf("[CLI] Falha: CHUNK seq=%d não confirmado após o limite de tentativas.%n", sequence);
                return false;
            }

            totalSentBytes += bytesRead;
            sequence++;

            int progress = (int) ((100.0 * totalSentBytes) / totalFileSize);
            System.out.printf("[CLI] Progresso: %d%% (%d/%d bytes)%n", progress, totalSentBytes, totalFileSize);
        }

        return true;
    }

    private boolean sendChunk(
            DatagramSocket socket,
            Device target,
            byte[] headerBytes,
            byte[] data,
            String id,
            int sequence,
            long totalSentBytes,
            long totalFileSize
    ) throws Exception {
        String base64Data = Base64.getEncoder().encodeToString(data);
        byte[] base64Bytes = base64Data.getBytes();

        byte[] chunkBytes = new byte[headerBytes.length + base64Bytes.length];
        System.arraycopy(headerBytes, 0, chunkBytes, 0, headerBytes.length);
        System.arraycopy(base64Bytes, 0, chunkBytes, headerBytes.length, base64Bytes.length);

        DatagramPacket packet = new DatagramPacket(chunkBytes, chunkBytes.length, target.getIpAddress(), target.getPort());

        double progressPercent = 100.0 * totalSentBytes / totalFileSize;
        int baseRetries = 5;
        int extraRetries = (progressPercent > 80) ? 3 : (progressPercent > 50 ? 2 : 0);
        int maxRetries = baseRetries + extraRetries;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            socket.send(packet);
            System.out.printf("[CLI] CHUNK seq=%d enviado (tentativa %d)%n", sequence, attempt);

            if (waitForChunkAck(socket, id)) {
                System.out.printf("[CLI] ACK recebido para CHUNK seq=%d%n", sequence);
                return true;
            }

            if (attempt >= 4) {
                try {
                    Thread.sleep(Math.min((attempt - 3) * 500L, 1500));
                } catch (InterruptedException ignored) {}
            }
        }

        return false;
    }

    private void sendEndMessage(File file, DatagramSocket socket, String id, Device target) {
        try {
            String hash = computeSHA256(file);
            String endMessage = Protocol.END + " " + id + " " + hash;
            byte[] endData = endMessage.getBytes();
            DatagramPacket packet = new DatagramPacket(endData, endData.length, target.getIpAddress(), target.getPort());

            for (int attempt = 1; attempt <= 3; attempt++) {
                socket.send(packet);
                System.out.printf("[CLI] END enviado (tentativa %d)%n", attempt);
                if (waitForEndAck(socket, id)) return;
            }

            System.err.println("[CLI] Falha ao validar a transferência com END.");
        } catch (Exception e) {
            System.err.printf("[CLI] Erro ao enviar END: %s%n", e.getMessage());
        }
    }

    private boolean waitForChunkAck(DatagramSocket socket, String expectedId) throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);

        try {
            socket.receive(response);
            String responseMsg = new String(response.getData(), 0, response.getLength()).trim();
            return responseMsg.equals("ACK " + expectedId);
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    private boolean waitForEndAck(DatagramSocket socket, String id) {
        byte[] buffer = new byte[1024];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);

        try {
            socket.receive(response);
            String resp = new String(response.getData(), 0, response.getLength());

            if (resp.equals(Protocol.ACK + " " + id)) {
                System.out.println("[CLI] ACK final recebido! Transferência concluída.");
                return true;
            } else if (resp.startsWith(Protocol.NACK)) {
                System.err.println("[CLI] NACK recebido: " + resp);
            }
            else
                System.out.println("[CLI] Mensagem desconhecida");

        } catch (SocketTimeoutException e) {
            System.out.println("[CLI] Timeout aguardando ACK final...");
        } catch (IOException e) {
            System.err.printf("[CLI] Erro ao aguardar ACK final: %s%n", e.getMessage());
        }

        return false;
    }
    private String computeSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
