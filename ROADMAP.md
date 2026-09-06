# 🗺️ Bedrock Java - Project Roadmap

Bem-vindo ao Roadmap oficial do **Bedrock Java**. Como um framework educacional com a premissa de **Zero Dependências Externas**, nosso objetivo de evolução não é adicionar bibliotecas de terceiros, mas sim recriar os padrões da engenharia moderna utilizando apenas a biblioteca padrão do JDK.

Nossa trilha é desenhada para acompanhar a curva real de aprendizado de um desenvolvedor: **Rotas & JSON ➡️ Validação & Robustez ➡️ Banco de Dados ➡️ Tempo Real ➡️ Segurança ➡️ Engenharia de Baixo Nível**.

---

## ✅ Versão 1.1 - *RESTful & Native JSON Engine* (Concluído)
*Foco: Transformar o Bedrock em uma ferramenta REST completa sem bibliotecas externas de JSON.*

- [x] **Verbos HTTP RESTful Completos:** Suporte nativo às anotações `@BedrockGet`, `@BedrockPost`, `@BedrockPut`, `@BedrockDelete` e `@BedrockPatch`.
- [x] **Motor JSON Nativo (`BedrockJson`):** Parser descendente recursivo (Lexer + Parser) e serializador por Reflexão para Java 21 Records e POJOs, sem Jackson ou Gson.
  - *Blindagem:* Limite de profundidade de aninhamento (`MAX_NESTING_DEPTH = 128`), suporte a notação científica, escapes Unicode e deserialização de coleções genéricas em Records.
- [x] **Binding Automático de DTOs:** Injeção direta de Records como parâmetros de métodos em Controllers com parsing automático de JSON do corpo da requisição.

---

## ✅ Versão 1.2 - *A Vida Real da API (Validação & Robustez)* (Concluído)
*Foco: Resolver as dores diárias de quem constrói APIs limpas e desacopladas, mantendo o princípio de que **"Explícito é melhor do que implícito"**.*

- [x] **Validação Didática de Dados:** Métodos auxiliares no `Context` para extrair e validar entradas sem anotações mágicas (`ctx.paramAsInt("id")`, `ctx.paramAsLong("id")`, `ctx.queryParamAsInt("page")`, decodificação de URL em `ctx.queryParam(...)`, `ctx.badRequest(Object)`, `ctx.notFound(Object)`).
  - *Conceito Ensinado:* Sanitização de dados de entrada, prevenção de `NumberFormatException`/`NullPointerException` e exceção com diagnóstico acionável (`BedrockValidationException`).
- [x] **Inversão de Interfaces no IoC (Letra 'D' do SOLID):** Capacidade de registrar e vincular interfaces para depender de abstrações (`app.bind(Interface.class, Impl.class)`).
  - *Exemplo:* `app.bind(IUserService.class, UserService.class)`
  - *Conceito Ensinado:* Princípio da Inversão de Dependência (SOLID) e facilidade de testes unitários isolados com Mocks (sem precisar de container IoC).
- [x] **Global Exception Handler Amigável (`app.onError`):** Interceptador central para capturar exceções da camada de domínio/controladores e padronizar o JSON de erro (estilo Problem Details RFC 7807), eliminando `try/catch` repetitivos.
  - *Conceito Ensinado:* Centralized Error Handling, polimorfismo de exceções e códigos de status HTTP semânticos (400, 404, 422, 500).

> 💡 **Decisão de Design Pedagógica:** Mantemos o registro de componentes e rotas 100% explícito (`app.register(...)` e `app.bind(...)`), rejeitando *Auto-Discovery* ou escaneamento mágico de pacotes por padrão. O aluno deve sempre ser capaz de dar `Ctrl+Clique` e ver exatamente onde cada peça do sistema é instanciada e conectada.

---

## ✅ Versão 1.3 - *Persistência Descomplicada (Banco de Dados sem Mágica)* (Concluído)
*Foco: Como salvar dados de verdade em disco antes de usar ORMs complexos, mantendo o `bedrock-core` com **Zero Dependências**.*

- [x] **Motor JDBC Transparente (`BedrockJdbc` & `RowMapper`):** Utilitário didático no Core usando puramente `java.sql.*` do JDK 21. Ensina `PreparedStatement` com parâmetros tipados (`?`), cursores com `ResultSet` e try-with-resources.
  - *Conceito Ensinado:* Como bancos de dados relacionais se comunicam com a JVM via drivers JDBC, sem o peso ou a caixa-preta de ORMs como Hibernate/JPA.
- [x] **Padrão Repository Educacional:** Implementação das interfaces `IUserRepository` e `SqliteUserRepository` separando SQL da regra de negócio (`UserService`).
  - *Conceito Ensinado:* Padrões de Arquitetura de Software (Repository Pattern), desacoplamento com SOLID 'D' e blindagem estrita contra SQL Injection.
- [x] **Injeção de Instâncias no IoC:** Suporte a `app.registerInstance(Class<T>, T instance)` para injetar recursos pré-configurados (como instâncias de `BedrockJdbc`) no container.
- [x] **SQLite Real no `bedrock-example`:** Driver `sqlite-jdbc` incluído unicamente no exemplo para demonstrar criação de schema (`CREATE TABLE IF NOT EXISTS`), chave primária autoincremento e persistência em arquivo (`bedrock.db`).

---

## ⚡ Versão 2.0 - *Comunicação em Tempo Real (WebSockets)*
*Foco: Entender como o WhatsApp Web e chats funcionam por baixo dos panos.*

- [ ] **Motor WebSockets (RFC 6455) do Zero:** Descer o nível para o `ServerSocketChannel` do Java NIO para manipular o Handshake TCP e o mascaramento de bits (Framing) dos WebSockets.
  - *Conceito Ensinado:* Protocolos de Rede TCP/IP, Handshake HTTP 101 Switching Protocols e manipulação de fluxos binários.
- [ ] **Anotação de Real-Time (`@BedrockSocket`):** Criar canais bidirecionais persistentes sobre Virtual Threads com consumo mínimo de memória.
  - *Conceito Ensinado:* Concorrência leve com Project Loom para conexões de longa duração.

---

## 🛡️ Versão 3.0 - *Segurança Prática*
*Foco: Proteger rotas e autenticar usuários sem a complexidade do Spring Security.*

- [ ] **Autenticação via Token (JWT Nativo):** Gerador e validador de tokens JWT (RFC 7519) utilizando as APIs criptográficas nativas do JDK (`java.security`).
  - *Conceito Ensinado:* Assinatura digital (HMAC-SHA256), cabeçalhos de autorização `Bearer` e proteção de recursos.
- [ ] **Rate Limiter Nativo:** Controle de vazão de requisições por IP.
  - *Conceito Ensinado:* Primitivas de concorrência do `java.util.concurrent` (`Semaphore`, `ReentrantLock` e algoritmo Token Bucket).

---

## 🎓 Versão 4.0 - *Engenharia Avançada da JVM*
*Foco: Desmistificar as maiores mágicas do ecossistema corporativo.*

- [ ] **Programação Orientada a Aspectos (AOP):** Introduzir suporte a anotações como `@BedrockTransactional` ou `@BedrockAsync`.
  - *Conceito Ensinado:* Uso de `java.lang.reflect.Proxy` (Dynamic Proxies) para interceptar chamadas em tempo de execução.
- [ ] **Event Bus Interno (Pub/Sub):** Sistema de disparo e escuta de eventos desacoplados dentro da aplicação.
  - *Conceito Ensinado:* Design Pattern `Observer` acoplado a filas em Virtual Threads.
- [ ] **Bedrock Telemetry (Métricas de Servidor):** Endpoint embutido `/bedrock/metrics` expondo a saúde do runtime.
  - *Conceito Ensinado:* Uso da API `java.lang.management` (JMX) para monitorar Heap Memory, Garbage Collection e Threads ativas.

---

### Como contribuir?
Se você quer aprender de verdade como a JVM funciona por baixo dos panos, escolha uma das metas do nosso Roadmap, entenda qual **Conceito de Engenharia** ela propõe ensinar, faça um Fork e envie seu Pull Request!
