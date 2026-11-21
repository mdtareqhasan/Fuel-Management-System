package com.fuelplatform.risk;

/**
 * One deduplicated demand observation for a single station and fuel.
 *
 * <p>Distinct from the simulator's raw row on purpose. By the time a row becomes a
 * {@code DemandObservationView} it has been:
 *
 * <ul>
 *   <li>filtered to {@code demand_liters}, never {@code served_liters} (INV-19),</li>
 *   <li>deduplicated on (station, fuel, tick), and</li>
 *   <li>sorted ascending by tick (INV-20).</li>
 * </ul>
 *
 * <p>Making the cleaned form a distinct type means no caller can accidentally compute a
 * forecast from a raw, unsorted, unfiltered row.
 *
 * @param stationId    the station this observation belongs to
 * @param fuel         the fuel type, one of DIESEL, PETROL or OCTANE
 * @param tick         the simulator tick this observation belongs to
 * @param simTime      the simulator's naive local time, used for the hour-of-day factor
 * @param demandLiters the real demand, in litres, for this tick
 */
public record DemandObservationView(
        String stationId,
        String fuel,
        long tick,
        String simTime,
        double demandLiters) {
}
