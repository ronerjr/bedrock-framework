# ADR 001: Implementação de WebSockets RFC 6455 via Java NIO e Virtual Threads

- **Status:** Aprovado e Implementado (Versão 2.0)
- **Data:** 2026-09-11
- **Decisores:** Comitê de Avaliação Arquitetural do Bedrock

---

## 1. Contexto do Problema
O Bedrock Framework necessitava de comunicação bidirecional em tempo real para a Versão 2.0 (WebSockets). No ecossistema corporativo Java convencional, utiliza-se Spring WebSocket ou Netty, que trazem centenas de classes abstratas, complexos event-loops reativos ou proxies em runtime, ocultando a mecânica do protocolo.

---

## 2. Decisão Arquitetural
Construir o motor WebSocket do zero (`BedrockWebSocketServer`) utilizando estritamente a biblioteca padrão do JDK 21:
1. `java.nio.channels.ServerSocketChannel` e `SocketChannel` dedicados para escuta e transporte TCP.
2. Manipulação explícita do Handshake HTTP 101 calculando SHA-1 + GUID da RFC 6455 em Base64.
3. Decodificação e codificação binária de frames com desmascaramento XOR in-place.
4. Despacho concorrente onde cada conexão ativa executa em sua própria **Virtual Thread** (`Thread.ofVirtual()`), com I/O síncrono e bloqueante simples.
5. Anotações semânticas leves (`@BedrockSocket`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`) com registro explícito no `BedrockApp`.

---

## 3. Parecer e Embasamento do Comitê de Arquitetura

### 🏛️ 1. Parecer de JVM e Concorrência
* **Veredito:** APROVADO
* **Embasamento:** Conforme demonstrado por **Brian Goetz em *Java Concurrency in Practice*** e a especificação da **JEP 444 (Virtual Threads)**, o modelo tradicional de "thread-per-connection" com Platform Threads era proibitivo pelo custo de stack (1MB por thread). Com as Virtual Threads do Project Loom, o custo cai para poucos bytes na Heap, permitindo que adotemos I/O síncrono/bloqueante legível sem risco de exaustão de threads e sem precisar de frameworks reativos como Netty. O uso de `ReentrantLock` para sincronizar envios de frames garante atomicidade sem causar *Carrier Thread Pinning* (evitando blocos `synchronized`).

### 🏛️ 2. Parecer de Design e SOLID
* **Veredito:** APROVADO
* **Embasamento:** Aderência estrita a **Joshua Bloch (*Effective Java*, Item 64)** ao expor a interface `BedrockWebSocketSession` para o usuário, desacoplando o código de domínio da implementação concreta do socket. O registro explícito no `BedrockApp` respeita **Robert C. Martin (*Clean Architecture*)**, rejeitando a "mágica de classpath scanning" e garantindo injeção de dependências transparente via IoC.

### 🏛️ 3. Parecer de Protocolos de Rede
* **Veredito:** APROVADO
* **Embasamento:** Conformidade de 100% com a **IETF RFC 6455**:
  - Handshake validado com o GUID `258EAFA5-E914-47DA-95CA-C5AB0DC85B11` (§1.3).
  - Enforce de mascaramento obrigatório em frames enviados pelo cliente com código de erro 1002 em caso de violação (§5.1).
  - Resposta automática de Pong com o mesmo payload ao receber Ping (§5.5.2).

### 🏛️ 4. Parecer de Didática Técnica e DevExperience
* **Veredito:** APROVADO
* **Embasamento:** Alinhado com a tese de **John Ousterhout (*A Philosophy of Software Design*)** sobre "Deep Modules". A interface do desenvolvedor é simples e declarativa (`@BedrockSocket`), enquanto a implementação interna não esconde os bits, documentada com comentários `🎓 BEDROCK TUTORIAL` passo a passo.
