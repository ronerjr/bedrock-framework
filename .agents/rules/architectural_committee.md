# Regra do Comitê de Avaliação Arquitetural do Bedrock

Ao planejar, desenhar, refatorar ou revisar qualquer funcionalidade no Bedrock Java Framework, o agente deve obrigatoriamente acionar a avaliação do **Comitê de Arquitetura (Bedrock Architecture Committee)**, composto por 4 perspectivas técnicas e suas literaturas de referência:

## 1. Especialista em JVM, Concorrência e Baixo Nível
- **Critérios:** Zero dependências externas no core (`bedrock-core`), uso de I/O síncrono limpo sobre Virtual Threads (Project Loom - JEP 444), sem bloqueios `synchronized` em I/O (usar `ReentrantLock` para evitar *Carrier Thread Pinning*), try-with-resources rigoroso.
- **Referências para Citação:** Goetz (Java Concurrency in Practice), JVM Specification (SE 21), JEP 444.

## 2. Especialista em Design de Software e SOLID
- **Critérios:** Princípio da Inversão de Dependência (SOLID 'D') explícito via `app.bind(Interface.class, Impl.class)`, injeção de dependências por construtor, imutabilidade com Records/classes finais, sem escaneamento cego de classpath ou anotações que façam "mágica oculta".
- **Referências para Citação:** Gamma et al. (GoF Design Patterns), Robert C. Martin (Clean Architecture), Joshua Bloch (Effective Java).

## 3. Especialista em Protocolos de Rede e Padrões IETF
- **Critérios:** Conformidade estrita com as RFCs da Internet (RFC 6455 para WebSockets, RFC 9110/9112 para HTTP, RFC 7807/9457 para Problem Details, RFC 7519 para JWT na v3.0). Tratamento correto de bits, bytes, mascaramento e códigos de status.
- **Referências para Citação:** IETF RFCs oficiais, Kurose & Ross (Computer Networking: A Top-Down Approach).

## 4. Especialista em Didática Técnica e DevExperience (Guardião da "Zero Mágica")
- **Critérios:** Todo código no core deve ser rastreável via `Ctrl+Clique` em menos de 100 linhas por método, comentários pedagógicos ricos `🎓 BEDROCK TUTORIAL` explicando o "porquê" físico de cada decisão, código autoexplicativo sem camadas misteriosas de proxies dinâmicos em tempo de execução.
- **Referências para Citação:** John Ousterhout (A Philosophy of Software Design - Deep Modules), Michael Feathers (Working Effectively with Legacy Code).

---
**Instrução Operacional:**
Ao apresentar planos de implementação (`implementation_plan.md`) ou revisões técnicas, inclua uma seção **"🏛️ Parecer do Comitê de Arquitetura"** avaliando a proposta sob essas 4 óticas e citando os livros/RFCs correspondentes.
