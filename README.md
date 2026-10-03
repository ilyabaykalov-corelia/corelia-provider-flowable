# corelia-provider-flowable

Адаптер Flowable OSS к `WorkflowProvider` и `TaskProvider` с id `flowable`.
Его подключает `corelia-workflow-service`; модуль не публикует самостоятельный
HTTP API.

## Ответственность

- развёртывает проверенный BPMN из customer configuration с checksum и не
  создаёт дубликат уже опубликованного ресурса;
- запускает и читает процессы, резервируя binding создания по document/type/
  idempotency key;
- отображает Flowable user tasks и доступные BPMN actions в canonical модели;
- валидирует namespaces и Corelia BPMN profile перед публикацией;
- передаёт разрешённую service task `WorkflowServiceTaskExecutor`, не помещая
  document business rules в BPMN adapter.

Выбирается свойствами `corelia.provider.workflow=flowable` и/или
`corelia.provider.tasks=flowable`. Требует Flowable engine и валидные bindings
в загруженном configuration package. В текущей реализации публикация definition
нуждается в deployment lock; изменение BPMN не мигрирует активные instances.

```bash
mvn -pl corelia-provider-flowable -am test
```

Контракт: [provider SPI](../docs/provider-spi.md); эксплуатационный контекст:
[workflow-service](../corelia-workflow-service/README.md).
