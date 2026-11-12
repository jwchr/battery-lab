import com.batterylab.personal.BatteryMath
import com.batterylab.personal.BatteryPoint
import com.batterylab.personal.RingBatteryProtocol

fun main() {
    val t = 1_000_000_000L
    val estimate = BatteryMath.estimate(listOf(
        BatteryPoint(t, 100, false),
        BatteryPoint(t + 3_600_000L, 94, false),
        BatteryPoint(t + 2 * 3_600_000L, 88, false)
    ))!!
    check(estimate.percentPerHour == 6.0)
    check(kotlin.math.abs(estimate.hoursFromFull - 16.666666) < 0.01)
    check(BatteryMath.estimate(listOf(BatteryPoint(t, 90, false))) == null)
    check(BatteryMath.estimate(listOf(
        BatteryPoint(t, 90, false), BatteryPoint(t + 3_600_000L, 88, false))) == null)
    check(BatteryMath.experimentDrain(t, t + 12 * 3_600_000L, 100, 76) == 2.0)
    check(BatteryMath.experimentDrain(t, t + 12 * 3_600_000L, 76, 100) == null)

    val packet = byteArrayOf(0x0b, 0x0b, 0x42, 0x06, 0x00, 0x05, 0x3f, 0x07, -1, 0x08, -1)
    check(RingBatteryProtocol.parseBatteryReport(packet) == (63 to false))
    check(RingBatteryProtocol.parseBatteryReport(byteArrayOf(0x0b, 0x0b, 0x02, 0x05, 100.toByte(), 0x06, 1.toByte())) == (100 to true))
    check(RingBatteryProtocol.parseBatteryReport(byteArrayOf(0, 0, 0)) == null)
    println("Passed: runtime projection, experiment drain, Galaxy Ring battery packet decoding")
}
