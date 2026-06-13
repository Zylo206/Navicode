package com.navicode.runtime.task;

import java.util.List;

public final class TaskCommandFormatter {
    private TaskCommandFormatter() {}

    public static String handle(DurableTaskManager manager, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim();
        if (normalized.equalsIgnoreCase("list")) {
            return formatList(manager.list(20));
        }
        if (normalized.regionMatches(true, 0, "list ", 0, 5)) {
            return formatList(manager.list(parseLimit(normalized.substring(5).trim(), 20)));
        }
        if (normalized.regionMatches(true, 0, "add ", 0, 4)) {
            DurableTask task = manager.enqueue(normalized.substring(4).trim());
            return "✅ 后台任务已提交: " + task.id() + "\n   /task log " + task.id();
        }
        if (normalized.regionMatches(true, 0, "new ", 0, 4)) {
            WorkTask task = manager.createWorkTask(normalized.substring(4).trim());
            return "✅ 工作任务已创建: " + task.id() + "  " + task.summary();
        }
        if (normalized.regionMatches(true, 0, "sub ", 0, 4)) {
            String rest = normalized.substring(4).trim();
            int split = rest.indexOf(' ');
            if (split <= 0 || split == rest.length() - 1) {
                return "❌ 用法: /task sub <parent_id> <任务摘要>";
            }
            try {
                WorkTask task = manager.createWorkSubtask(rest.substring(0, split), rest.substring(split + 1));
                return "✅ 子任务已创建: " + task.id() + "  " + task.summary();
            } catch (IllegalArgumentException e) {
                return "❌ " + e.getMessage();
            }
        }
        if (normalized.equalsIgnoreCase("board") || normalized.equalsIgnoreCase("board --all")) {
            return formatBoard(manager.listWorkTasks(normalized.toLowerCase().contains("--all")));
        }
        if (normalized.regionMatches(true, 0, "start ", 0, 6)) {
            return updateWorkTask(manager, normalized.substring(6), WorkTaskStatus.IN_PROGRESS, "已开始");
        }
        if (normalized.regionMatches(true, 0, "block ", 0, 6)) {
            return updateWorkTask(manager, normalized.substring(6), WorkTaskStatus.BLOCKED, "已标记阻塞");
        }
        if (normalized.regionMatches(true, 0, "done ", 0, 5)) {
            return updateWorkTask(manager, normalized.substring(5), WorkTaskStatus.DONE, "已完成");
        }
        if (normalized.regionMatches(true, 0, "abandon ", 0, 8)) {
            return updateWorkTask(manager, normalized.substring(8), WorkTaskStatus.ABANDONED, "已放弃");
        }
        if (normalized.equalsIgnoreCase("gate")) {
            String reminder = manager.stopGateReminder();
            return reminder.isBlank() ? "✅ 没有 open/in_progress 工作任务" : reminder;
        }
        if (normalized.regionMatches(true, 0, "cancel ", 0, 7)) {
            String id = normalized.substring(7).trim();
            return manager.cancel(id)
                    ? "⏹️ 已请求取消后台任务: " + id
                    : "❌ 未找到可取消的后台任务: " + id;
        }
        if (normalized.regionMatches(true, 0, "log ", 0, 4)) {
            return manager.find(normalized.substring(4).trim())
                    .map(TaskCommandFormatter::formatLog)
                    .orElse("❌ 未找到后台任务: " + normalized.substring(4).trim());
        }
        return """
                ❌ 未知 /task 子命令: %s
                可用命令：
                  /task
                  /task list [N]
                  /task add <任务内容>
                  /task cancel <task_id>
                  /task log <task_id>
                  /task new <任务摘要>
                  /task sub <parent_id> <任务摘要>
                  /task board [--all]
                  /task start|block|done|abandon <id> [说明]
                  /task gate
                """.formatted(payload).trim();
    }

    public static String formatList(List<DurableTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "📭 暂无后台任务";
        }
        StringBuilder sb = new StringBuilder("📋 最近 ").append(tasks.size()).append(" 个后台任务：\n");
        for (DurableTask task : tasks) {
            sb.append("   ")
                    .append(task.id())
                    .append("  ")
                    .append(task.status().value())
                    .append("  ")
                    .append(task.durationMs())
                    .append("ms  ")
                    .append(task.shortPrompt())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatLog(DurableTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 后台任务 ").append(task.id()).append('\n');
        sb.append("状态: ").append(task.status().value()).append('\n');
        sb.append("创建: ").append(task.createdAt()).append('\n');
        if (task.startedAt() != null) {
            sb.append("开始: ").append(task.startedAt()).append('\n');
        }
        if (task.finishedAt() != null) {
            sb.append("结束: ").append(task.finishedAt()).append(" (").append(task.durationMs()).append("ms)\n");
        }
        sb.append("\n任务:\n").append(task.prompt()).append('\n');
        if (task.error() != null && !task.error().isBlank()) {
            sb.append("\n错误:\n").append(task.error()).append('\n');
        }
        if (task.result() != null && !task.result().isBlank()) {
            sb.append("\n结果:\n").append(task.result()).append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatBoard(List<WorkTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "📭 暂无工作任务";
        }
        StringBuilder sb = new StringBuilder("📋 工作任务板：\n");
        for (WorkTask task : tasks) {
            int depth = Math.max(0, task.id().split("\\.").length - 1);
            sb.append("   ")
                    .append("  ".repeat(depth))
                    .append(task.id())
                    .append("  ")
                    .append(task.status().value())
                    .append("  ")
                    .append(task.summary())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private static String updateWorkTask(DurableTaskManager manager,
                                         String raw,
                                         WorkTaskStatus status,
                                         String successPrefix) {
        ParsedWorkTaskUpdate update = parseWorkTaskUpdate(raw);
        if (update.id().isBlank()) {
            return "❌ 请提供任务 id";
        }
        return manager.updateWorkTaskStatus(update.id(), status, update.note())
                ? "✅ " + successPrefix + ": " + update.id()
                : "❌ 未找到工作任务: " + update.id();
    }

    private static ParsedWorkTaskUpdate parseWorkTaskUpdate(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedWorkTaskUpdate("", "");
        }
        String trimmed = raw.trim();
        int split = trimmed.indexOf(' ');
        if (split < 0) {
            return new ParsedWorkTaskUpdate(trimmed, "");
        }
        return new ParsedWorkTaskUpdate(trimmed.substring(0, split), trimmed.substring(split + 1).trim());
    }

    private record ParsedWorkTaskUpdate(String id, String note) {}

    private static int parseLimit(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
