package com.sdp.eventbus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

	@Test
	void deliversEveryEventFromConcurrentPublishers() throws InterruptedException {
		EventBus eventBus = new EventBus();
		int publisherCount = 20;
		int eventsPerPublisher = 50;

		List<DomainEvent> received = new CopyOnWriteArrayList<>();
		eventBus.events().subscribe(received::add);

		ExecutorService executor = Executors.newFixedThreadPool(publisherCount);
		CountDownLatch start = new CountDownLatch(1);

		for (int publisherId = 0; publisherId < publisherCount; publisherId++) {
			int id = publisherId;
			executor.submit(() -> {
				await(start);
				for (int i = 0; i < eventsPerPublisher; i++) {
					eventBus.publish(new TestEvent(id + "-" + i));
				}
			});
		}

		start.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		assertThat(received).hasSize(publisherCount * eventsPerPublisher);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private record TestEvent(String id) implements DomainEvent {
		@Override
		public String eventType() {
			return "TEST_EVENT";
		}
	}
}
