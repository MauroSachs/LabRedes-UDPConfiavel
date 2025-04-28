package com.t1labredes;

import com.t1labredes.server.Server;

public class Main {
    public static void main(String[] args) {
        new Thread(new Server()).start();  // escuta e processa mensagens
        new Thread(new ConsoleInterface()).start();
    }
}
