# Original User Request

## 2026-09-11T03:54:29Z

Implementar a Versão 2.0 (Real-Time WebSockets RFC 6455) no Bedrock Java Framework com zero dependências externas no core, foco estritamente pedagógico e alta robustez técnica sobre Java 21 e Virtual Threads.

Working directory: c:\Users\roner\Documents\repo\bedrock-framework
Integrity mode: development

## 🎯 Princípio Central: Combater a "Mágica de Anotações" (Sem Caixas-Pretas)
O foco do Bedrock não é ser dogmático contra bibliotecas por capricho, mas sim combater o vício de "colocar uma anotação e tudo acontecer por baixo dos panos sem ninguém entender como". 

No Spring, um @EnableWebSocket ou @ServerEndpoint faz o desenvolvedor esquecer que existe TCP, handshake HTTP 101 e frames binários mascarados. No Bedrock:
- Mecânica Transparente: O aluno deve conseguir rastrear o fluxo: o byte entra no socket TCP -> os cabeçalhos HTTP são lidos -> o hash SHA-1 do handshake é calculado -> a conexão é promovida -> os frames chegam e o desmascaramento com XOR de 4 bytes acontece explicitamente.
- Registro Explícito: Nada de escaneamento mágico no classpath que descobre classes sozinho. O desenvolvedor registra explicitamente no BedrockApp (app.enableWebSockets(port) e app.register(ChatSocket.class)).
- Abstração Equilibrada: Anotações como @BedrockSocket, @OnOpen e @OnMessage servem apenas como contratos semânticos limpos para o desenvolvedor, mas a implementação no core deve ser legível e didática (sem 15 camadas de proxies dinâmicos gerados em runtime).
- Core Enxuto: No bedrock-core, mantemos a preferência pelo JDK 21 padrão (java.nio, java.net, java.security) para que o desenvolvedor aprenda a usar o próprio ecossistema Java sem intermediários desnecessários.

## Requirements

### R1. Motor WebSocket RFC 6455 do Zero (Zero-Dependency & Java NIO)
Construir um servidor WebSocket independente (BedrockWebSocketServer) usando estritamente as APIs padrão do JDK 21 (java.nio.channels.ServerSocketChannel, java.nio.channels.SocketChannel, java.nio.ByteBuffer e java.security.MessageDigest).
- Manipular o Handshake HTTP inicial (GET com cabeçalhos Upgrade: websocket, Connection: Upgrade, Sec-WebSocket-Key, Sec-WebSocket-Version: 13).
- Calcular o cabeçalho de resposta Sec-WebSocket-Accept utilizando o hash SHA-1 da chave recebida concatenada com o GUID da RFC 6455 (258EAFA5-E914-47DA-95CA-C5AB0DC85B11), codificado em Base64.
- Responder com o status HTTP 101 Switching Protocols e assumir a conexão em modo full-duplex sobre o SocketChannel.
- Implementar o parser e empacotador de Frames da RFC 6455:
  - Decodificação de frames binários de entrada: bit FIN, RSV1-3, Opcode (0x1 Text, 0x2 Binary, 0x8 Close, 0x9 Ping, 0xA Pong), bit de MASK e chave de mascaramento de 4 bytes (desmascaramento XOR obrigatório para frames vindos do cliente conforme a RFC).
  - Suporte a comprimentos de payload: 7-bit (até 125 bytes), 16-bit estendido (126 a 65535 bytes) e 64-bit estendido.
  - Envio de frames de servidor para cliente (sem máscara, conforme especificação RFC 6455).
  - Resposta automática de Pong (Opcode 0xA) ao receber frames de Ping (Opcode 0x9), e tratamento de encerramento amigável (Close, Opcode 0x8).

### R2. Arquitetura Concorrente com Virtual Threads (Project Loom)
Cada conexão WebSocket estabelecida deve ter seu loop de leitura e gerenciamento de ciclo de vida executado em uma Virtual Thread dedicada (Thread.ofVirtual().name("ws-client-", ...).start(...)), garantindo alta escalabilidade concorrente com consumo mínimo de recursos e bloqueio I/O síncrono limpo, eliminando a complexidade de frameworks reativos ou Netty.

### R3. API e Abstrações Pedagógicas (@BedrockSocket)
Criar uma API limpa, explícita e educativa que permita aos desenvolvedores exporem endpoints WebSocket com facilidade:
- Anotação de classe @BedrockSocket(path = "/chat") ou equivalente para mapeamento de rotas WebSocket.
- Anotações de ciclo de vida para métodos:
  - @OnOpen: disparado quando o handshake é concluído, injetando a sessão (BedrockWebSocketSession).
  - @OnMessage: disparado quando um frame de texto/mensagem chega, injetando a sessão e a mensagem (String message).
  - @OnClose: disparado na desconexão do cliente.
  - @OnError: disparado em caso de exceções na conexão ou parsing de frame.
- Abstração de Sessão (BedrockWebSocketSession):
  - send(String text): envio de mensagens de texto thread-safe.
  - close() / close(int code, String reason): fechamento da conexão.
  - broadcast(String text) ou gerenciamento de salas/clientes conectados para permitir cenários de chat em tempo real.
- Registro explícito no BedrockApp:
  - Método de ativação no builder: app.enableWebSockets(int port) ou configuração fluente, e suporte a registro no IoC (app.register(MyChatSocket.class)).

### R4. Padrão Educacional Rígido (🎓 BEDROCK TUTORIAL)
Todo novo código no bedrock-core deve manter:
- Zero dependências de terceiros no bedrock-core/pom.xml.
- Comentários Javadoc ricos no padrão 🎓 BEDROCK TUTORIAL explicando a física de cada conceito:
  - O motivo do Handshake 101 e da constante mágica da RFC 6455.
  - O cálculo do hash SHA-1 e Base64.
  - A anatomia de um Frame WebSocket (bits de controle, desmascaramento XOR).
  - A diferença entre I/O bloqueante tradicional e Virtual Threads em WebSockets.

### R5. Demonstração Prática no bedrock-example
Implementar um exemplo completo de aplicação no módulo bedrock-example:
- Um endpoint de chat ou telemetria em tempo real (ChatWebSocket) utilizando @BedrockSocket.
- Uma página HTML/JS simples ou teste demonstrando a troca de mensagens bidirecional em tempo real.

## Acceptance Criteria

### Testes Automatizados e Cobertura
- [ ] Testes unitários para o handshake WebSocket (Sec-WebSocket-Accept validado contra vetores de teste oficiais da RFC 6455).
- [ ] Testes unitários para codificação e decodificação de frames (frames mascarados pequenos, médios de 16-bit e grandes).
- [ ] Testes de integração end-to-end simulando cliente WebSocket conectando, enviando mensagem de texto, recebendo eco/broadcast, respondendo a Ping e executando Close handshake sem travamento.
- [ ] Todos os testes anteriores do framework (81 testes existentes das v1.1, v1.2 e v1.3) continuam passando com 100% de sucesso (mvn test).

### Zero Dependências e Padrões de Qualidade
- [ ] Nenhuma dependência externa adicionada ao bedrock-core/pom.xml (somente JDK 21 padrão: java.nio, java.net, java.security).
- [ ] Build completo mvn clean verify executado com sucesso e sem warnings críticos de Javadoc.
- [ ] ROADMAP.md atualizado marcando a Versão 2.0 como concluída ([x]).
