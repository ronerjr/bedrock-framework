# 🗺️ Bedrock Java - Project Roadmap

Bem-vindo ao Roadmap oficial do **Bedrock Java**. Como um framework educacional com a premissa de "Zero Dependências Externas", nosso objetivo de evolução não é adicionar milhares de integrações de terceiros, mas sim recriar padrões de engenharia modernos utilizando apenas a biblioteca padrão do JDK.

Nossa evolução é dividida em *Milestones* claros, focados em melhorar a arquitetura (IoC), a robustez HTTP e, finalmente, abrir as portas para o Real-Time.

---

## 🚀 Versão 1.1 - *The "Solid Foundation" Update*
*Foco: Elevar o IoC Container a um padrão de mercado e adicionar robustez no tratamento de requisições.*

- [ ] **Inversão de Interfaces no IoC:** Capacidade de registrar interfaces para depender de abstrações, respeitando o princípio 'D' do SOLID.
  - *Exemplo:* `app.bind(ILinkService.class, LinkServiceImpl.class)`
- [ ] **Auto-Discovery (Component Scanning):** Eliminar a necessidade de registrar classes manualmente. O framework deve varrer o classpath na largada procurando por `@BedrockController` e `@BedrockService`.
- [ ] **Global Exception Handler:** Um interceptador central para capturar exceções não tratadas (ex: `EntityNotFoundException`) e padronizar o JSON de erro (Problema Details RFC 7807), limpando os `try/catch` dos controladores.
- [ ] **Motor JSON Robusto:** Evoluir nosso algoritmo de Reflection para suportar e serializar corretamente nativos do Java moderno, como `LocalDate`, `UUID`, Enums e Listas Genéricas.

---

## ⚡ Versão 2.0 - *The "Real-Time & Scale" Update*
*Foco: Abandonar o HttpServer nativo restrito ao HTTP/1.1 para suportar TCP direto e conexões bidirecionais.*

- [ ] **Motor WebSockets (RFC 6455) do Zero:** Descer o nível para o `ServerSocketChannel` do Java NIO para manipular o Handshake TCP e o mascaramento de bits (Framing) dos WebSockets.
- [ ] **Anotação de Real-Time:** Criação da anotação `@BedrockSocket("/chat")` para manter canais persistentes abertos sobre Virtual Threads consumindo recursos mínimos.
- [ ] **Motor de Roteamento Avançado:** Roteador com suporte a Regex em *Path Variables* (ex: `/users/{id:[0-9]+}`) e parse automático de *Query Parameters* (`?sort=asc`) direto para o `Context`.
- [ ] **Bedrock TestContext (Server-less):** Um framework interno para injetar um `Context` simulado (Mock) nos Controladores, permitindo testes de integração instantâneos sem precisar alocar portas TCP no SO.

---

## 🔮 Futuro (V3.0+) - *The "Persistence & Security" Vision*
*Ideias de longo prazo para tornar o Bedrock viável para mini-projetos monolíticos de ponta a ponta.*

- [ ] **Bedrock Data (ORM Minimalista):** Um wrapper educacional por cima da API JDBC nativa do Java, fornecendo um modelo de `ActiveRecord` ou `Repository` simples sem a complexidade de um Hibernate.
- [ ] **Security Pipeline Integrado:** Filtros nativos para JWT (JSON Web Tokens) parsing and validation, proteção contra CSRF e rate-limiting (Throttling) utilizando tokens-bucket em memória.

---

### Como contribuir?
Se você tem interesse em aprender como a JVM funciona por baixo dos panos, escolha uma das *Issues* do nosso Roadmap, faça um *Fork*, e submeta seu PR! O aprendizado é garantido.
