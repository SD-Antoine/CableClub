package slt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Long-running background tasks (ROM randomization / analysis) polled by the UI. */
public final class Jobs {

    @FunctionalInterface
    public interface Task {
        Map<String, Object> run(Consumer<String> progress) throws Exception;
    }

    private static final class Job {
        final String id;
        final String label;
        volatile String state = "running";
        volatile String message = "En attente…";
        volatile String error;
        volatile Map<String, Object> result;
        final List<String> steps = new ArrayList<>();
        final long startedAt = System.currentTimeMillis();
        volatile long endedAt;

        Job(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "slt-job");
        t.setDaemon(true);
        return t;
    });

    public String start(String label, Task task) {
        String id = Ids.id(12);
        Job job = new Job(id, label);
        jobs.put(id, job);
        pool.submit(() -> {
            try {
                job.result = task.run(msg -> {
                    job.message = msg;
                    synchronized (job.steps) {
                        job.steps.add(msg);
                    }
                });
                job.state = "done";
                job.message = "Terminé";
            } catch (Http.Fail f) {
                job.state = "error";
                job.error = f.getMessage();
            } catch (Throwable t) {
                Log.error("Tâche « " + label + " » en échec", t);
                job.state = "error";
                job.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            } finally {
                job.endedAt = System.currentTimeMillis();
            }
        });
        prune();
        return id;
    }

    public Map<String, Object> status(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new Http.Fail(404, "Tâche inconnue");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.id);
        m.put("label", job.label);
        m.put("state", job.state);
        m.put("message", job.message);
        synchronized (job.steps) {
            m.put("steps", new ArrayList<>(job.steps));
        }
        m.put("error", job.error);
        m.put("elapsedMs", (job.endedAt == 0 ? System.currentTimeMillis() : job.endedAt) - job.startedAt);
        if ("done".equals(job.state)) {
            m.put("result", job.result);
        }
        return m;
    }

    private void prune() {
        long limit = System.currentTimeMillis() - 3_600_000L;
        jobs.values().removeIf(j -> j.endedAt != 0 && j.endedAt < limit);
    }
}
