### Projeto de Comunicação P2P com Protocolo Personalizado via UDP

Este projeto é parte do **Trabalho 1 do Laboratório de Redes de Computadores**, cujo objetivo é desenvolver uma aplicação distribuída de comunicação entre dispositivos, utilizando **UDP e sockets**. Cada dispositivo implementa um protocolo personalizado para descoberta, troca de mensagens e envio confiável de arquivos.

## 📦 Arquitetura e Execução com Docker Compose

O sistema é composto por múltiplos dispositivos (`device1`, `device2`, `device3`) simulados como containers Docker interconectados por uma rede virtual. Cada dispositivo roda uma instância do programa com sua própria identidade.

### ✅ Pré-requisitos

- Docker
- Docker Compose

### ▶️ Como executar

1. Clone este repositório e acesse a pasta do projeto:

```bash
git clone <repo-url>
cd <nome-do-projeto>
```

2. Construa e inicie os dispositivos:

```bash
docker-compose up --build
```

3. Acesse o terminal de um dispositivo (ex: device1):

```bash
docker exec -it <nome-do-container> bash
```

4. A interface de linha de comando estará ativa no terminal do container.

> Todos os arquivos devem ser colocados ou lidos a partir do diretório `./arquivos` (que é montado como `/app/files` nos containers).

---

Ou, para executar diretamente sem docker:

```bash
SERVER_PORT=9876 CLIENT_ID=device1 java -jar target/app.jar
```

---

## 🛰️ Funcionamento do Protocolo

### Descoberta automática

- Cada dispositivo envia uma mensagem `HEARTBEAT <nome>` via **broadcast** a cada 5 segundos.
- Os outros dispositivos escutam e mantêm uma lista atualizada dos dispositivos ativos.
- Se um dispositivo não enviar heartbeat por **mais de 10 segundos**, ele será removido da lista.

### Tipos de mensagens

- `HEARTBEAT <nome>` – Sinal de presença na rede
- `TALK <id> <mensagem>` – Mensagem entre pares com confirmação (`ACK <id>`)
- `FILE <id> <nome-arquivo> <tamanho>` – Início da transferência de arquivo
- `CHUNK <id> <seq> <dados>` – Bloco de dados codificado em base64
- `END <id> <hash>` – Final da transferência com verificação de integridade
- `ACK <id>` – Confirmação de recebimento
- `NACK <id> <motivo>` – Rejeição ou falha (ex: hash inválido)

---

## 💻 Interface de Linha de Comando (CLI)

A CLI está disponível dentro de cada container e suporta os seguintes comandos:

### 🔹 `devices`

Lista os dispositivos ativos, mostrando:

- Nome
- Endereço IP e porta
- Tempo desde o último heartbeat

### 🔹 `talk <nome> <mensagem>`

Envia uma mensagem de texto para o dispositivo especificado. A CLI informa se o `ACK` foi recebido.

### 🔹 `sendfile <nome> <arquivo>`

Envia um arquivo (presente em `./arquivos`) para outro dispositivo:

- Mostra progresso da transferência (em blocos ou porcentagem)
- Realiza verificação de integridade via hash SHA-256
- Tenta reenvio em caso de falha ou perda de pacotes

---

## 🧪 Requisitos de Confiabilidade Implementados

- Confirmação com `ACK` e retransmissão
- Detecção de mensagens duplicadas
- Verificação de ordem para `CHUNK`
- Verificação de integridade com `END` + hash SHA-256
- Transferência em blocos (streaming), sem carregar o arquivo inteiro na memória

---

## 🗂️ Organização dos arquivos

- Enviar: Coloque arquivos na pasta `./arquivos/`
- Receber: Os arquivos recebidos são salvos com prefixo `received_<CLIENT_ID>_` em `/app/files`

---

📁 Projeto acadêmico — Redes de Computadores | Trabalho 1 | 2025
```
