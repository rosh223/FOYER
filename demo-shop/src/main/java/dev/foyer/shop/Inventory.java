package dev.foyer.shop;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Seats per category, sold atomically; at most one booking per Foyer pass. */
@Component
public class Inventory {

    public static final List<String> CATEGORIES = List.of("Gold", "Silver", "Bronze");
    public static final List<Integer> PRICES = List.of(6500, 4500, 2500);

    private final Map<String, AtomicInteger[]> stock = new ConcurrentHashMap<>();
    private final Map<String, Integer[]> initial = new ConcurrentHashMap<>();
    private final Set<String> usedPasses = ConcurrentHashMap.newKeySet();
    private final AtomicLong bookings = new AtomicLong(1000);

    public Inventory(ShopProperties props) {
        for (var e : props.events()) {
            initial.put(e.slug(), e.tickets().toArray(Integer[]::new));
            reset(e.slug());
        }
    }

    public void reset(String slug) {
        Integer[] init = initial.get(slug);
        AtomicInteger[] s = new AtomicInteger[init.length];
        for (int i = 0; i < init.length; i++) s[i] = new AtomicInteger(init[i]);
        stock.put(slug, s);
    }

    public void resetPasses() {
        usedPasses.clear();
    }

    public int left(String slug, int category) {
        return stock.get(slug)[category].get();
    }

    public int leftTotal(String slug) {
        int sum = 0;
        for (AtomicInteger a : stock.get(slug)) sum += a.get();
        return sum;
    }

    public record Booking(boolean ok, String reason, long id) {
    }

    public Booking buy(String slug, int category, int quantity, String passId) {
        if (quantity < 1 || quantity > 4) return new Booking(false, "You can book 1 to 4 tickets", 0);
        if (!usedPasses.add(passId)) return new Booking(false, "This pass has already been used for a booking", 0);
        AtomicInteger s = stock.get(slug)[category];
        while (true) {
            int left = s.get();
            if (left < quantity) {
                usedPasses.remove(passId);
                return new Booking(false, left == 0 ? "That category is sold out" : "Only " + left + " left in that category", 0);
            }
            if (s.compareAndSet(left, left - quantity)) return new Booking(true, null, bookings.incrementAndGet());
        }
    }
}
