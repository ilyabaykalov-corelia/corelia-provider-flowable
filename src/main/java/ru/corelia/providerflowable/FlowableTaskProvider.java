package ru.corelia.providerflowable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.TaskService;
import org.flowable.engine.RepositoryService;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.provider.TaskProvider;
import ru.corelia.provider.model.TaskSearchRequest;
import ru.corelia.provider.model.TaskStatus;
import ru.corelia.provider.model.WorkflowAction;
import ru.corelia.provider.model.WorkflowTask;
import ru.corelia.support.Json;
import tools.jackson.databind.JsonNode;

/** Преобразует Flowable user tasks в канонические модели Corelia. */
@Component("flowableTaskProvider")
public final class FlowableTaskProvider implements TaskProvider {
    private static final String ASSIGNEE_ROLE = "coreliaAssigneeRole";
    private final TaskService tasks;
    private final RepositoryService repository;
    private final FlowableWorkflowBindings bindings;

    public FlowableTaskProvider(TaskService tasks, RepositoryService repository, FlowableWorkflowBindings bindings) {
        this.tasks = tasks;
        this.repository = repository;
        this.bindings = bindings;
    }

    @Override
    public List<WorkflowTask> search(TaskSearchRequest request, AuthContext auth) {
        var found = new LinkedHashMap<String, WorkflowTask>();
        add(found, queryForUser(actor(auth)), request.statuses());
        if (!auth.roles().isEmpty()) add(found, queryForGroups(auth.roles()), request.statuses());
        return List.copyOf(found.values());
    }

    @Override
    public List<WorkflowTask> findByDocument(String documentId, AuthContext auth) {
        return search(new TaskSearchRequest(Set.of("NEW", "ASSIGNED", "STARTED")), auth).stream()
                .filter(task -> documentId.equals(task.documentId())).toList();
    }

    @Override
    public WorkflowTask task(String taskId, AuthContext auth) {
        return search(new TaskSearchRequest(Set.of("NEW", "ASSIGNED", "STARTED")), auth).stream()
                .filter(task -> taskId.equals(task.id())).findFirst().orElse(null);
    }

    @Override
    public String roleLabel(String role, AuthContext auth) {
        return role == null || role.isBlank() ? null : role;
    }

    @Override
    public void start(String taskId, AuthContext auth) {
        WorkflowTask task = required(taskId, auth);
        if (!task.assignee().isEmpty() && !actor(auth).equals(task.assignee()))
            throw new ApiException(409, "Задача уже назначена другому пользователю");
        if (task.assignee().isEmpty()) {
            if (!task.assigneeRole().isEmpty())
                tasks.setVariableLocal(taskId, ASSIGNEE_ROLE, task.assigneeRole());
            tasks.claim(taskId, actor(auth));
        }
        tasks.startProgress(taskId, actor(auth));
    }

    @Override
    public void complete(String taskId, Map<String, JsonNode> parameters, AuthContext auth) {
        WorkflowTask task = required(taskId, auth);
        if (!task.assignee().isEmpty() && !actor(auth).equals(task.assignee()))
            throw new ApiException(403, "Задача назначена другому пользователю");
        var values = new LinkedHashMap<String, Object>();
        parameters.forEach((name, value) -> {
            if (value != null && !value.isNull()) values.put(name, flowableValue(value));
        });
        tasks.complete(taskId, values);
    }

    private void add(Map<String, WorkflowTask> target, List<Task> source, Set<String> statuses) {
        for (Task task : source) {
            WorkflowTask mapped = map(task);
            if (statuses.isEmpty() || statuses.contains(mapped.status())) target.putIfAbsent(mapped.id(), mapped);
        }
    }

    private List<Task> queryForUser(String username) {
        return prepared(tasks.createTaskQuery().taskCandidateOrAssigned(username)).list();
    }

    private List<Task> queryForGroups(List<String> roles) {
        return prepared(tasks.createTaskQuery().taskCandidateGroupIn(roles)).list();
    }

    private static TaskQuery prepared(TaskQuery query) {
        return query.active().includeProcessVariables().includeIdentityLinks();
    }

    private WorkflowTask required(String taskId, AuthContext auth) {
        WorkflowTask task = task(taskId, auth);
        if (task == null) throw new ApiException(404, "Активная задача не найдена");
        return task;
    }

    private WorkflowTask map(Task task) {
        Map<String, Object> variables = task.getProcessVariables();
        String documentId = string(variables.get(FlowableWorkflowProvider.DOCUMENT_ID));
        String documentType = string(variables.get(FlowableWorkflowProvider.DOCUMENT_TYPE));
        return new WorkflowTask(task.getId(), documentId, documentType, status(task.getState()),
                value(task.getAssignee()), value(task.getAssignee()), fallback(string(tasks.getVariableLocal(task.getId(), ASSIGNEE_ROLE)), role(tasks.getIdentityLinksForTask(task.getId()))),
                fallback(task.getName(), task.getTaskDefinitionKey()), value(task.getDescription()),
                attributes(variables.get(FlowableWorkflowProvider.ATTRIBUTES)),
                actions(task, documentType));
    }

    private List<WorkflowAction> actions(Task task, String documentType) {
        var actions = CoreliaBpmnActions.actions(repository.getBpmnModel(task.getProcessDefinitionId()), task.getTaskDefinitionKey());
        return actions.isEmpty() ? bindings.actions(documentType, task.getTaskDefinitionKey()) : actions;
    }

    private static String status(String state) {
        return switch (state == null ? "" : state.toUpperCase(java.util.Locale.ROOT)) {
            case "CREATED" -> TaskStatus.NEW.name();
            case "CLAIMED" -> TaskStatus.ASSIGNED.name();
            case "INPROGRESS" -> TaskStatus.STARTED.name();
            default -> TaskStatus.fromProvider(state).name();
        };
    }

    private static Map<String, JsonNode> attributes(Object source) {
        if (!(source instanceof String json) || json.isBlank()) return Map.of();
        JsonNode values = Json.parse(json);
        if (!values.isObject()) return Map.of();
        var result = new LinkedHashMap<String, JsonNode>();
        values.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static String role(List<? extends IdentityLinkInfo> links) {
        if (links == null) return "";
        return links.stream().filter(link -> "candidate".equalsIgnoreCase(link.getType()))
                .map(IdentityLinkInfo::getGroupId).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("");
    }

    private static Object flowableValue(JsonNode value) {
        if (value.isTextual()) return value.asString();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.asDouble();
        return Json.write(value);
    }

    private static String actor(AuthContext auth) {
        return auth.taskUsername().isBlank() ? auth.login() : auth.taskUsername();
    }

    private static String value(String value) { return value == null ? "" : value; }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static String fallback(String value, String fallback) { return value == null || value.isBlank() ? value(fallback) : value; }
}
