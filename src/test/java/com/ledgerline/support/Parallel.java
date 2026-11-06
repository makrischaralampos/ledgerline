package com.ledgerline.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs tasks on a thread pool, releasing the first wave simultaneously to maximise contention. */
public final class Parallel {

    public record Outcome<T>(T value, Throwable error) {
        public boolean ok() {
            return error == null;
        }
    }

    private Parallel() {}

    public static <T> List<Outcome<T>> run(int threads, List<Callable<T>> tasks)
        throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(
                Math.min(threads, tasks.size())
            );
            CountDownLatch go = new CountDownLatch(1);

            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(
                    pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return task.call();
                    })
                );
            }

            ready.await(30, TimeUnit.SECONDS);
            go.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(
                        new Outcome<>(future.get(90, TimeUnit.SECONDS), null)
                    );
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
