package com.t1labredes.server;

import com.t1labredes.device.Device;
import com.t1labredes.protocol.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Server implements Runnable {

    private static Server instance;
    private final Map<String, FileReceiver> fileTransfers = new HashMap<>();
    private final Map<String, Device> activeDevices = new ConcurrentHashMap<>();
    private DatagramSocket socket;

    public static Server getInstance() {
        return instance;
    }

    public Map<String, Device> getActiveDevices() {
        return activeDevices;
    }

    @Override
    public void run() {
        instance = this;
        try {
            String serverPort = System.getenv("SERVER_PORT");
            String deviceName = System.getenv("CLIENT_ID");

            if (!checkEnvVariables(serverPort, deviceName)) return;

            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Integer.parseInt(serverPort)));

            System.out.printf("[Server] Inicializado em %s:%d%n",
                    socket.getLocalAddress().getHostAddress(), socket.getLocalPort());

            createAliveTimer(deviceName);
            messageLoop();

        } catch (IOException e) {
            System.err.println("[Server] Erro ao iniciar: " + e.getMessage());
        }
    }

    private void messageLoop() throws IOException {
        while (true) {
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(receivePacket);
            String message = new String(receivePacket.getData(), 0, receivePacket.getLength());
            processMessage(receivePacket, message);
        }
    }

    private void createAliveTimer(String deviceName) {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    String heartbeat = Protocol.HEARTBEAT + " " + deviceName;
                    byte[] sendData = heartbeat.getBytes();
                    InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                    DatagramPacket packet = new DatagramPacket(sendData, sendData.length, broadcast, socket.getLocalPort());
                    socket.send(packet);
                } catch (Exception ignored) {}

                Iterator<Map.Entry<String, Device>> iterator = activeDevices.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Device> entry = iterator.next();
                    if (entry.getValue().isInactive(10000)) {
                        System.out.println("[Server] Dispositivo removido por inatividade: " + entry.getKey());
                        iterator.remove();
                    }
                }
            }
        }, 0, 5000);
    }

    private static boolean checkEnvVariables(String serverPort, String deviceName) {
        if (serverPort == null || serverPort.isBlank()) {
            System.err.println("[Server] SERVER_PORT não definido!");
            return false;
        }
        if (deviceName == null || deviceName.isBlank()) {
            System.err.println("[Server] CLIENT_ID não definido!");
            return false;
        }
        return true;
    }

    private void processMessage(DatagramPacket packet, String message) {
        String[] tokens = message.split(" ", 2);
        if (tokens.length < 1) {
            System.out.println("processMessage: mensagem corrompida");
            return;
        }

        switch (tokens[0]) {
            case Protocol.HEARTBEAT -> handleHeartbeat(packet, tokens[1]);
            case Protocol.TALK -> handleTalk(packet, tokens[1]);
            case Protocol.FILE -> handleFile(packet, tokens[1]);
            case Protocol.CHUNK -> handleChunk(packet, tokens[1]);
            case Protocol.END -> handleEnd(packet, tokens[1]);
            default -> System.out.println("[Server] Tipo desconhecido: " + tokens[0]);
        }
    }

    private void handleHeartbeat(DatagramPacket packet, String body) {
        String name = body.trim();
        InetAddress ip = packet.getAddress();
        int port = packet.getPort();

        String localName = System.getenv("CLIENT_ID");
        if (name.equals(localName)) return;

        Device device = new Device(name, ip, port);
        boolean isNew = !activeDevices.containsKey(name);
        activeDevices.put(name, device);
        device.updateHeartbeatTime();

        if (isNew) System.out.printf("[Server] Novo dispositivo detectado: %s (%s:%d)%n", name, ip.getHostAddress(), port);
    }

    private void handleTalk(DatagramPacket packet, String body) {
        String[] parts = body.split(" ", 2);
        if (parts.length < 2) return;

        String id = parts[0];
        String msg = parts[1];

        System.out.printf("[Server] TALK de %s: %s%n", packet.getAddress().getHostAddress(), msg);

        sendAck(packet.getAddress(), packet.getPort(), id);
    }

    private void handleFile(DatagramPacket packet, String body) {
        String[] parts = body.split(" ", 3);
        if (parts.length < 3) return;

        String id = parts[0];
        String fileName = parts[1];
        String fileSize = parts[2];

        System.out.printf("[Server] FILE recebido: %s (%s bytes)%n", fileName, fileSize);

        sendAck(packet.getAddress(), packet.getPort(), id);

        try {
            fileTransfers.put(id, new FileReceiver(fileName));
        } catch (Exception e) {
            System.err.println("[Server] Erro FileReceiver: " + e.getMessage());
        }
    }

    private void handleChunk(DatagramPacket packet, String body) {
        String[] parts = body.split(" ", 3);
        if (parts.length < 3) return;

        String id = parts[0];
        int seq = Integer.parseInt(parts[1]);
        String base64Data = parts[2];

        try {
            byte[] data = Base64.getDecoder().decode(base64Data);
            FileReceiver receiver = fileTransfers.get(id);
            if (receiver == null) return;

            boolean isNew = receiver.writeChunk(seq, data);
            if (isNew)
                System.out.printf("[Server] CHUNK %d salvo (%s)%n", seq, receiver.getFileName());
            else
                System.out.printf("[Server] CHUNK %d ja recebido, ignora: (%s)%n", seq, receiver.getFileName());


            sendAck(packet.getAddress(), packet.getPort(), id);

        } catch (Exception e) {
            System.err.println("[Server] Erro CHUNK: " + e.getMessage());
        }
    }

    private void handleEnd(DatagramPacket packet, String body) {
        String[] parts = body.split(" ", 2);
        if (parts.length < 2) return;

        String id = parts[0];
        String receivedHash = parts[1];

        FileReceiver receiver = fileTransfers.get(id);
        if (receiver == null) return;

        try {
            if (receiver.isValidated()) {
                sendAck(packet.getAddress(), packet.getPort(), id);
                return;
            }
            receiver.close();
            String localHash = receiver.calculateHash();

            if (localHash.equals(receivedHash)) {
                System.out.printf("[Server] Arquivo %s validado com sucesso.%n", receiver.getFileName());
                sendAck(packet.getAddress(), packet.getPort(), id);
                receiver.markValidated();
            } else {
                System.err.printf("[Server] Hash inválido: %s != %s%n", localHash, receivedHash);
                String nack = Protocol.NACK + " " + id + " hash mismatch";
                socket.send(new DatagramPacket(nack.getBytes(), nack.length(), packet.getAddress(), packet.getPort()));
                fileTransfers.remove(id);
            }
        } catch (Exception e) {
            System.err.println("[Server] Erro END: " + e.getMessage());
        }
    }

    private void sendAck(InetAddress address, int port, String id) {
        try {
            String ack = Protocol.ACK + " " + id;
            byte[] ackData = ack.getBytes();
            DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, address, port);
            socket.send(ackPacket);
        } catch (IOException e) {
            System.err.println("[Server] Erro ao enviar ACK: " + e.getMessage());
        }
    }
}