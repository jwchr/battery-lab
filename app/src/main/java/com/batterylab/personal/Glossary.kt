package com.batterylab.personal

data class GlossaryEntry(val section: String, val term: String, val meaning: String, val howWorkedOut: String)

/** Plain-language descriptions of the calculations, in Stats screen order. No live data. */
object Glossary {
    val entries: List<GlossaryEntry> = buildList {
        var section = "Phone battery capacity"
        fun entry(term: String, meaning: String, how: String) {
            add(GlossaryEntry(section, term, meaning, how))
        }

        entry("Capacity when full", "About how much charge the phone can hold, in milliamp-hours (mAh). The charge counter is Android's own estimate of charge left.",
            "Uses the latest positive charge counter at 100% in the last 30 days. Otherwise it takes the middle estimate from non-charging readings at 20% or higher: mAh left times 100 divided by percent. With no recent estimate, it uses the combined full-charge and slope samples described below.")
        entry("Percent of advertised capacity", "Estimated capacity compared with the model's advertised typical capacity.",
            "Capacity when full divided by advertised typical mAh, times 100. The gauge stops at 100%, even if the text reports more. This comparison needs a known model specification.")
        entry("Advertised and rated minimum", "Two factory capacity figures, when the app knows them for this model.",
            "Looked up in the app's model specifications. They are reference values, not measurements of this battery.")
        entry("Battery health label", "Android's current health category, such as Good or Overheating.",
            "Read directly from Android's battery status. It is a category, not the estimated percent of original capacity.")
        entry("Charge cycles", "The lifetime cycle count reported by Android, when available.",
            "Shown on Android 14 or newer only when Android reports a positive count. Battery Lab does not calculate this tile from its history.")
        entry("Chemistry", "The battery technology reported by Android.", "Uses Android's current battery technology text when available.")
        entry("Voltage now", "The battery's current electrical voltage, in volts (V).",
            "Android's positive millivolt value divided by 1,000.")
        entry("Temperature now", "The current battery temperature, in degrees Celsius.",
            "Android's positive temperature value, in tenths of a degree, divided by 10.")
        entry("Full-charge readings", "A second capacity estimate from times the phone appeared fully charged.",
            "Each uninterrupted run at 100% qualifies if Android reports full, the run lasts at least 15 minutes, or unplugging is observed within it. Keep its highest counter value between 2,000 and 7,000 mAh. Show the middle of these samples, averaging the two middle values for an even count, plus the sample count.")
        entry("Slope method", "Capacity inferred from how the charge counter falls as percent falls. A fitted line smooths out uneven percent steps.",
            "Use the first reading at each percent from 15% through 98% in uninterrupted discharges. Require at least 6 levels spanning 30 points, fit a straight line to mAh versus percent, and multiply its slope by 100. Keep estimates from 1,000 through 10,000 mAh and show their middle value and count.")
        entry("Capacity spread and samples", "How much the full-charge and slope estimates differ, and how many estimates were available.",
            "Pool both methods' samples and use their middle value as the fallback capacity. With at least 4 samples, the displayed plus-or-minus spread is half the distance between the 25% and 75% positions in the sorted samples, using proportional values between positions. The sample count is the number pooled.")

        section = "Will it last?"
        entry("Current percent", "The latest saved battery level used for planning.",
            "A device is included only if its latest reading is less than 6 hours old. Each device uses its own history.")
        entry("Hours to reserve", "Estimated hours before reaching the 15% reserve, rather than empty.",
            "Subtract 15 from the latest percent, stop at zero, and divide by the middle historical drain rate. Each discharge stretch must last at least 1 hour and lose at least 4 points; a charge, rise over 2 points, or gap over the stretch allowance ends it. That allowance is at least 8 hours, or 3 check intervals if longer.")
        entry("Likely hours range", "Hours to reserve at faster and slower historical drain rates.",
            "Use the rates at the 75% and 25% positions in the sorted stretch rates, with proportional values between positions. Divide the usable percent above 15% by each rate. This is a history-based range, not a guarantee.")
        entry("Target and full-charge hours", "Whether the typical estimate reaches the chosen 8, 12, 18, or 24 hours.",
            "Compare hours to reserve with the selected target. Hours from full is 85 divided by the middle drain rate. If even that falls short, the card says a full charge will not reach the target.")
        entry("Charge minutes first", "An estimated top-up time to reach the target with a 15% reserve.",
            "Needed points equal typical hourly drain times target hours, plus 15, minus current percent, with a minimum of zero. Cap the top-up at the room left to 100%, then divide by the middle observed charging speed. Only charges timed for at least 10 minutes count; the suggested time is shown as at least 1 minute.")
        entry("One-charger order", "Which devices to charge first if more than one falls short.",
            "Include devices whose typical hours miss the target and sort them by hours to reserve, shortest first.")

        section = "What's draining it"
        entry("Apps on screen share", "The share of observed battery loss above the idle estimate. It shows what happened while apps were visible, not a separate meter for each app.",
            "Subtract idle loss from total loss, divide by total loss, and round to a whole percent. Analysis uses available Android screen history from the last 7 days and skips charging, rising steps, and steps over the gap allowance. The card needs at least 5 points of loss and an idle baseline.")
        entry("Idle drain share", "The estimated share used by the phone on its own, including idle use underneath screen time.",
            "Multiply the screen-off hourly rate by all included hours and cap it at total loss. The tile is 100 minus the rounded apps-on-screen share, so the two tiles add to 100%.")
        entry("Screen time", "Hours with the screen on within the battery steps used in the analysis.",
            "Add the overlap between Android's screen-on history and the included non-charging reading steps. Time outside those steps is excluded.")
        entry("An hour, screen off", "The idle baseline, in battery percentage points per hour.",
            "Select steps with the screen on for at most 5% of their time. Require at least 1 hour in those steps, then divide their total loss by their total hours. Use charge-counter loss when both counters and capacity are usable; otherwise use whole percent steps.")
        entry("An hour, screen on", "Estimated hourly loss while the screen is on, including the idle baseline.",
            "Subtract estimated screen-off loss from total loss and divide by screen-on hours. Needs at least 30 minutes of screen time and a result at least as high as the idle rate. The full-charge hours caption is 100 divided by this rate; the screen-off caption uses its own rate.")
        entry("Top app percent", "An app's estimated share of all observed battery loss, above idle use.",
            "Share each step's screen-on loss by time each app was visible, then subtract the idle cost of that time. Scale positive app totals so they add up to total loss above idle, and divide each by all loss for its displayed percent. Show up to 8 rows; hidden apps and screen time without a known app go into Other.")
        entry("App screen hours and mAh", "Time an app was visible, and its estimated charge use above idle. mAh means milliamp-hours, a measure of charge.",
            "Screen hours are the app's overlap with included battery steps. Its mAh equals its assigned percentage points divided by 100, times estimated capacity. This is a correlation with screen use, so background work can affect it.")
        entry("App watts", "Average whole-phone power while that app was visible, including idle use. A watt (W) measures energy used per second.",
            "For each step, convert the app's assigned screen-on loss to energy using capacity and available voltage, then divide summed energy by its screen hours. Unlike the app share, this includes idle use during screen time. Needs capacity, voltage, and at least 10 minutes on screen.")
        entry("Background hours (bg)", "Hours an app ran a visible background service while the screen was off.",
            "Merge overlapping service intervals, keep only time inside included battery steps, and remove screen-on overlap. Show the caption at 0.1 hour or more. It does not assign extra battery loss to the app.")

        section = "Asleep and idle"
        entry("Typical idle drain", "The middle hourly drain rate across quiet stretches of phone history.",
            "Each stretch must be unplugged for at least 4 hours, with known screen state and at most 5% screen-on time. Rate is percent lost divided by hours; the typical rate is the middle sorted rate, averaging the two middle values for an even count. Charges, percent rises, and steps beyond the gap allowance break stretches; trailing cached widget repeats are trimmed.")
        entry("Best idle stretch", "The quiet stretch with the lowest hourly drain.",
            "Choose the qualifying stretch with the smallest percent loss divided by hours. Its caption gives its start time and duration.")
        entry("Worst idle stretch", "The quiet stretch with the highest hourly drain.",
            "Choose the qualifying stretch with the largest percent loss divided by hours. Its caption gives its start time and duration.")
        entry("Unusual idle stretches", "Quiet stretches that drained much faster than typical.",
            "Needs at least 5 stretches. Flag a rate above the middle rate plus 3 times the middle absolute distance from that rate, and at least 0.5 points per hour above it. Notes mark at least half of available readings on cellular or Always-On Display, or an average temperature of at least 35°C.")

        section = "How long to charge"
        val chargeEstimateRule = "Prefer the middle directly timed duration, averaging the two middle values for an even count. Otherwise keep the longest available directly timed upper part and add the middle 10-point step times below it, or sum steps alone. Missing steps below the lowest measured step borrow its time; other missing steps prevent an estimate. Crossing times assume even charging between readings."
        entry("0 to 100%", "Estimated minutes to charge from empty to full.", chargeEstimateRule)
        entry("20 to 80%", "Estimated minutes through the middle of a charge.", chargeEstimateRule)
        entry("80 to 100%", "Estimated minutes for the final part of a charge.", chargeEstimateRule)
        entry("From your usual level", "Estimated minutes from the usual observed charge-start level to full.",
            "The usual level is the rounded middle starting percent of watched charging runs, not necessarily the true plug-in level. Show this tile only for a usual level from 1% through 90%. Estimate its time with the same direct-duration and 10-point-step rules as the other charging tiles.")
        for (lo in 0..90 step 10) {
            entry("$lo to ${lo + 10}% step", "Typical minutes for this 10-point part of a charge, for each device.",
                "Use watched charging runs that cover both levels and estimate the exact crossing times between readings. Sort their durations and take the middle, averaging the two middle values for an even count. The caption counts charges covering this step; gaps beyond the step allowance and a reported charging pause end runs.")
        }
        entry("Charge sample labels", "Whether a time was directly observed, added from steps, or partly estimated.",
            "A measured charge count means that many runs covered the full span. Added up from 10% steps means all parts were measured but no full-span sample was used. Estimate means an unseen low-end part borrowed a higher step's time.")
        entry("Last charge", "The latest watched run's start level, highest level, and time to first reach that high.",
            "Take the latest qualifying charging run and time from its first reading to the first reading at its highest level. Time parked at that level afterward does not add to the duration.")
        entry("20 to 80% range", "The fastest and slowest directly watched times across this span.",
            "Take the smallest and largest durations of runs covering both 20% and 80%, using estimated crossing times between readings. Show the range when both exist and differ.")

        section = "Charging speed"
        for ((lo, hi) in listOf(20 to 50, 50 to 80, 80 to 100)) {
            entry("$lo to $hi%", "Typical phone charging minutes for this band, grouped by charger type.",
                "Use charging runs that cover both levels and estimate crossing times between readings. For each charger type, take the middle duration, averaging the two middle values for an even count. A gap beyond the step allowance or Android reporting a charging pause ends a run.")
        }
        entry("Charger type and charge count", "Android's reported plug type and how many watched phone charges belong to that group.",
            "Each run uses its most common positive plug-type value. Count runs with at least two charging readings and a net percent gain. Unseen upward jumps are excluded.")
        entry("15-minute top-up", "Estimated points gained in 15 minutes starting at 20%.",
            "Walk the middle 20-to-50, 50-to-80, and 80-to-100 band times in order, pooled across charger types. Use a proportional part if time runs out within a band, and stop at the first missing band. The gain cannot exceed 80 points.")

        section = "Honesty audit"
        entry("Slow finish", "Charge and time added after first displaying 100% while still charging.",
            "Follow uninterrupted charging readings at 100% until Android reports full or the counter stops rising. For runs with more than one reading, take the middle added mAh and middle duration separately, averaging the two middle values for an even count.")
        entry("100% hold and hidden buffer", "How long the display stays full after unplugging, and how much charge is used before 99% appears.",
            "Require an observed unplug at 100%, then uninterrupted non-charging 100% readings followed by 99%. Time from that first unplugged reading to 99%; counter loss over the same endpoints gives mAh. Show the middle duration and middle positive counter loss across qualifying holds.")
        for ((name, band) in listOf("Top" to "70% through 95%, including 95%", "Middle" to "40% up to but not including 70%", "Bottom" to "15% up to but not including 40%")) {
            entry("$name mAh per 1%", "Charge held by each displayed percentage point in the $name band. mAh means milliamp-hours.",
                "Assign each non-charging, non-rising step by the average of its two endpoint percentages: $band. Divide total counter loss in mAh, including flat-percent steps, by total points lost. Require at least 3 points, reject counter rises above 1,000 microamp-hours, and accept results above zero through 500 mAh per point.")
        }
        entry("Top versus bottom ratio", "Whether a percent near the top holds more charge than a percent near the bottom.",
            "Divide top-band mAh per point by bottom-band mAh per point. Both must be available; 1 means equal.")
        entry("Below 15% and middle drain", "Hourly drain near empty and across the middle of the battery.",
            "Group non-charging, non-rising steps by the average endpoint percent: 1% through 15%, or 20% through 80%. In each band, divide total points lost by total hours, including flat steps. Each band needs at least half an hour and 2 points of loss; steps over the gap allowance are excluded.")
        entry("Bottom cliff ratio", "How much faster the low end drains compared with the middle.",
            "Divide the below-15% hourly rate by the middle-band rate. The card calls a ratio above 1.3 faster at the low end. The comparison shows observed rates, not the reason for a difference.")
        for (level in listOf(80, 50, 20)) {
            entry("Resting voltage at $level%", "Battery voltage under light load near $level%, in millivolts (mV).",
                "Take the middle voltage at ${level - 2}% through ${level + 2}%, averaging the two middle values for an even count. Require not charging, a recorded display state other than on, current magnitude at most 250 milliamps, and 20°C through 30°C. Current units must first be verified against charge-counter changes.")
        }
        entry("Internal resistance", "An estimate of how much the battery's voltage sags under extra load, in milliohms.",
            "After verifying current units, compare adjacent non-charging readings at most 30 minutes and 1 point apart. Require a current-magnitude change of at least 200 milliamps and divide the opposing voltage change by that current change. Keep values from 5 through 1,000 milliohms and show the middle value.")
        entry("Android new cycles", "The change in Android's reported lifetime cycle count during the saved phone history.",
            "Keep positive cycle counts and subtract the first from the last. Need at least two counts; zero reports are treated as unavailable.")
        entry("Charge-counter cycles", "Full batteries' worth of charge discharged in the qualifying phone steps.",
            "Sum positive counter drops in non-charging, non-rising steps within the step gap allowance. Convert to mAh and divide by the combined capacity estimate. Charging throughput is not added.")
        entry("Percent-step cycles", "Full batteries' worth of displayed percent lost in the audit's qualifying steps.",
            "Sum percent drops in non-charging, non-rising steps within the step gap allowance, including flat steps as zero. Divide by 100. These stricter steps can differ from the By the numbers total.")
        entry("Widget pairs and average difference", "How closely the widget's battery percent matches direct reports for each device.",
            "Pair each widget reading with the nearest reference within 2 minutes for the phone or 5 minutes for wearables. Count pairs and average the absolute percent-point differences. Reference reports come from Android, the watch companion, or direct ring reads already stored; opening this guide starts no connection.")
        entry("Widget bias and worst difference", "Whether the phone widget tends to read high or low, and its largest mismatch.",
            "For each matched pair, subtract the direct percent from the widget percent. Bias is the average signed difference, so positive means the widget reads higher. Worst is the largest absolute difference.")
        entry("Phone widget lag", "Typical minutes before the widget catches up with an Android percent drop.",
            "Time from an Android drop to the first widget reading at that level or lower, using zero when already caught up. Stop matching at a charge or percent rise, and keep lags only through 360 minutes. Show the middle lag, averaging the two middle values for an even count.")

        section = "Prediction report card"
        entry("2, 4, and 8 hours ahead", "Separate checks of predicted battery percent at three future times, for each device.",
            "Compare each stored forecast with the percent at its target time, estimated evenly between nearby readings within 30 minutes on each side. An exact reading at the target also works. Charging or a rise over 2 points removes affected targets; missing nearby data leaves that target ungraded.")
        entry("Off by", "Average prediction error in percentage points, ignoring whether it was high or low.",
            "For each graded target, subtract actual percent from predicted percent, take the absolute value, and average. Predictions are floored at zero. Battery Lab and Android are scored separately, and Android's estimate is available only for the phone.")
        entry("Bias", "Whether predictions usually overestimate or underestimate the remaining percent.",
            "Average predicted percent minus actual percent for that device, target time, and prediction source. Positive means too optimistic; negative means too pessimistic.")
        entry("Sample count (n) and waiting", "How many forecasts were graded, or are still waiting for their future checks.",
            "Each n counts forecasts with an actual percent for that target; Android also needs its own prediction. Waiting counts forecasts still marked pending. New forecasts are made at most hourly when a recent unplugged reading and a usable drain estimate exist, so these are not counts of every reading.")

        section = "Change journal"
        entry("Before and after counts", "How many quiet phone stretches can be compared around a recorded change.",
            "Use up to 14 stretches ending before the change window and up to 14 starting after the change. For an Android update, also skip the first 6 hours afterward. Notes have no measured effect, and changes to other devices are not assessed with phone idle data.")
        entry("Idle drain change and range", "The change in average idle drain, in points per hour, with a range showing uncertainty.",
            "Subtract the before mean from the after mean. The range is a 95% Welch interval, a comparison that allows different variation and sample counts in the two groups. Positive means more drain afterward; require at least 3 stretches on each side.")
        entry("Preliminary and established", "How much before-and-after history supports the comparison.",
            "Preliminary requires at least 3 stretches on each side. Established requires at least 7 on each side. These labels describe sample counts, not proof that the change caused the difference.")
        entry("Other changes flagged", "A warning that cellular or Always-On Display use also changed between the groups.",
            "Compare each group's average share of readings on cellular and Always-On Display. Flag an absolute change of at least 25 percentage points. Cellular is reported first if both qualify.")

        section = "Heat and charge level"
        for ((tempName, tempRule) in listOf("Below 30°C" to "below 30°C", "30 through 38°C" to "30°C through 38°C", "Above 38°C" to "above 38°C")) {
            for ((chargeName, chargeRule) in listOf("below 20%" to "below 20%", "20 through 80%" to "20% through 80%", "above 80%" to "above 80%")) {
                entry("$tempName, $chargeName", "Hours observed in this temperature and charge-level box.",
                    "Add each reading step's hours to the box set by its earlier reading: $tempRule and $chargeRule. Exactly 80% belongs to the middle column, even though the last column's heading starts at 80%. Require a temperature and skip steps over the gap allowance; the card needs at least 1 hour overall.")
            }
        }

        section = "By the numbers"
        entry("Tracked", "Elapsed time since the earliest saved reading among the devices shown.",
            "Subtract that earliest time from now. This includes gaps when no readings were taken. Show rounded hours below 2 days, otherwise days.")
        entry("Readings saved", "The number of readings included in the device statistics.",
            "Add the device reading counts after excluding invalid percent values and isolated large glitches. The per-day caption divides by tracked days, with a minimum denominator of 1 hour.")
        entry("Full batteries used and points", "Total observed battery loss, with 100 points counted as one full battery. A point is one step on the 0-to-100 battery display.",
            "Count new lows since each charge, so a small percent wobble does not count the same loss twice. A rise over 2 points resets the baseline as an unseen charge; isolated large glitches are excluded. Drops across gaps can still count in this total even though gap hours are excluded from time-based totals; divide points by 100 for full batteries.")
        entry("Charges spotted", "The number of charging sessions found for each device.",
            "Count watched charging runs with a net gain, plus rises of more than 2 points between unplugged readings. Runs already charging when history begins or still charging at its end can count. A step over the gap allowance splits watched runs.")
        entry("Lowest seen", "The lowest saved percent after invalid values and isolated large glitches are removed.",
            "Choose the smallest remaining percent for the device. The caption shows when that value was first found in time order.")
        entry("Longest unplugged", "The longest unbroken observed time on battery for each device.",
            "Time a run of unplugged readings until charging, a rise over 2 points, or a gap beyond the stretch allowance breaks it. That allowance is at least 8 hours, or 3 check intervals if longer. It is elapsed time between readings, not a measured full-to-empty cycle.")

        section = "Charging habits"
        entry("Average plug-in", "The average starting percent of charges with a known starting level.",
            "Average starting percentages of sessions whose plug-in level is known, including inferred charges. Exclude runs first seen already charging or after a gap. This is an arithmetic average, not the middle sorted level used by How long to charge.")
        entry("Average unplug", "The average highest observed percent of detected charges.",
            "Average the ending percentages of all detected charge sessions, including inferred and still-open sessions. It can therefore include a last observed level without a witnessed unplug.")
        entry("Seconds per 1% at best", "The fastest observed average charging pace, expressed as seconds per point.",
            "For watched charges lasting at least 5 minutes with a positive gain, divide points gained by minutes and select the highest rate. Divide 60 by that rate and round. Time parked at the peak is excluded.")
        entry("Parked at 100%", "Hours the phone was both marked charging and already full.",
            "For steps within the gap allowance, add elapsed hours when the earlier reading is charging at 100%.")
        entry("At 80% or more", "Hours at a high charge level, whether plugged in or not.",
            "For steps within the gap allowance, add elapsed hours when the earlier percent is at least 80%.")
        entry("Charges stopped short", "The share of watched, ended charges that finished below 100%.",
            "Select observed sessions with a witnessed end, count those ending below 100%, and divide by all such sessions. Unseen jumps and runs still open or interrupted by gaps are excluded. Multiply by 100 and round.")
        entry("Spent under 20%", "Hours at a low battery level.",
            "For steps within the gap allowance, add elapsed hours when the earlier percent is below 20%. Exactly 20% does not count.")
        entry("Time charging", "The share of covered time marked as charging.",
            "Use the earlier reading's charging flag for each step within the gap allowance. Divide charging hours by charging plus on-battery hours and round to a whole percent. A missing charging flag counts as on battery.")

        section = "Around the clock"
        entry("Hourly bars", "Average battery loss per hour for each local clock hour, separately for each device.",
            "Split non-charging, non-rising reading steps across local hour boundaries by elapsed time. Sum loss and hours for each clock hour, then divide loss by hours. Skip steps over the gap allowance and show an hour only after at least 30 minutes of included time.")
        entry("Hungriest and calmest hours", "The clock hours with the highest and lowest average drain rates.",
            "Compare the available hourly bar rates and select the largest and smallest. The calmest caption is omitted when both select the same hour.")

        section = "Showdown"
        entry("Full-charge hours and rank", "Projected full-to-empty runtime for each device, ordered longest first.",
            "Use only readings inside the chosen devices' shared first-to-last history overlap. Devices without at least 2 shared days are left out. Multiply discharge-stretch hours by 100 and divide by points lost. Stretches need 1 hour and 4 points; a charge, rise over 2 points, or gap over the stretch allowance ends one. Show at least two qualifying devices. This projects runtime from percent loss, not measured capacity or a full-to-empty run; coverage and charging time can differ.")

        section = "Head to head"
        entry("Battery used per day", "Observed loss per calendar day, with 100% meaning one full battery's worth.",
            "Use only readings inside the chosen devices' shared first-to-last history overlap. Devices without at least 2 shared days are left out. Divide each device's observed points used by the same overlap duration in days. Need two devices with two readings inside. Days include gaps, so coverage differences can undercount use; full batteries per day is the result divided by 100.")
        entry("Charges a week", "Detected charges scaled to a week at the observed pace.",
            "Detect charges using only readings inside the same shared period as Battery used per day. Divide the count by that shared duration in days and multiply by 7. Detected charges include inferred jumps and observed runs with a gain; sessions outside the period are excluded.")
        entry("Usually apart", "The middle elapsed time between detected charge starts.",
            "Use charges detected from readings inside the shared comparison period. Sort their start times and find the hours between adjacent pairs. Take the middle interval, averaging the two middle values for an even count. Needs at least two detected charges and can include gaps in collection.")

        section = "Over time"
        for ((term, span) in listOf("24 hours" to "1 day", "7 days" to "7 days", "30 days" to "30 days", "90 days" to "90 days", "6 months" to "182 days", "1 year" to "365 days", "All time" to "all recorded history")) {
            entry(term, "Projected full-to-empty hours at the combined drain rate seen over $span, for each device.",
                "Use discharge stretches lasting at least 1 hour with at least 4 points lost. Multiply included stretch hours by 100 and divide by included points lost, rather than averaging their projected runtimes. A stretch crossing the window's start contributes only its time share and the same share of its drop, assuming even drain. The caption counts overlapping stretches or warns when history is shorter than the window.")
        }

        section = "Drain spread"
        val spreadRule = "Use whole discharge stretches ending within the last 30 days, including ones starting earlier; each lasts at least 1 hour and loses at least 4 points. Needs at least 5 stretches."
        entry("Typical rate", "A middle stretch's drain rate in percentage points per hour.",
            "$spreadRule Sort points-lost-per-hour rates and take the value at the rounded-up half-count position, so an even count uses the lower middle rate.")
        entry("Most stretches range", "Low and high rate cutoffs covering the central part of the sorted stretches.",
            "$spreadRule For each cutoff, multiply the stretch count by 10% or 90%, round that position up, and take that sorted rate. These are the 10th and 90th percentiles, meaning rate cutoffs by position.")
        entry("Calmest stretch", "The stretch with the lowest hourly loss, with its start time and duration.",
            "$spreadRule Compare points lost divided by hours and choose the lowest rate.")
        entry("Hungriest stretch", "The stretch with the highest hourly loss, with its start time and duration.",
            "$spreadRule Compare points lost divided by hours and choose the highest rate.")
        for ((name, rule) in listOf("Under 1" to "below 1", "1 to 2" to "at least 1 and below 2", "2 to 4" to "at least 2 and below 4", "4 to 8" to "at least 4 and below 8", "8 or more" to "at least 8")) {
            entry("$name points per hour", "The number of qualifying stretches in this drain-rate band.",
                "$spreadRule Count stretches whose points lost divided by hours is $rule. Each stretch counts once, regardless of duration; the first bar also gives the total stretch count.")
        }

        section = "Weekdays vs weekends"
        for ((name, days) in listOf("Weekdays" to "Monday through Friday", "Weekends" to "Saturday or Sunday")) {
            entry("$name rate", "Battery loss per hour in stretches assigned to $days.",
                "Use whole discharge stretches ending within the last 30 days; assign each by the local day of its midpoint, without splitting at midnight. Stretches need at least 1 hour and 4 points lost. Each side shows only with at least 3 stretches and 10 total hours; divide that side's total points lost by its total hours.")
            entry("$name stretches and hours", "How much discharge history supports this side of the comparison.",
                "Count the whole stretches assigned to $days by their midpoint and add their full durations. Include stretches ending within the last 30 days even if they began earlier. The side needs at least 3 stretches and 10 hours.")
        }
        entry("Weekend difference", "How much faster or slower the weekend rate was relative to weekdays.",
            "When both sides qualify, subtract the weekday rate from the weekend rate, divide by the weekday rate, and multiply by 100. At least +5% is called faster and at most -5% slower; anything between is called about the same. The difference shows what happened, not why.")

        section = "Temperature"
        entry("Average", "The average saved phone battery temperature, with Celsius and Fahrenheit shown.",
            "Use all readings with a temperature from 0°C through 70°C. Average the values equally, rather than weighting by elapsed time. Fahrenheit equals Celsius times 9 divided by 5, plus 32.")
        entry("Hottest", "The highest valid saved temperature and when it was recorded.",
            "Choose the largest temperature from readings within 0°C through 70°C. Use its first occurrence in the supplied history for the timestamp.")
        entry("Coolest", "The lowest valid saved temperature.",
            "Choose the smallest temperature from readings within 0°C through 70°C.")
        entry("While charging", "The average valid temperature in readings marked charging.",
            "Average the 0°C-through-70°C readings marked charging. The warmer-or-cooler caption compares that result with the average of readings explicitly marked not charging. Readings with unknown charging state contribute to the overall average but neither subgroup.")

        // These comparisons all use the same battery-side energy total as renderFun.
        val energyRule = "Total battery energy in watt-hours (Wh) equals full batteries used times estimated capacity in mAh times 3.87 volts, divided by 1,000."
        section = "What it costs"
        entry("Electricity so far", "Estimated cost of energy that passed through the phone battery since tracking began.",
            "$energyRule Divide Wh by 1,000 to get kilowatt-hours (kWh), then multiply by the price in cents per kWh. Use the saved rate, or 18 cents per kWh until one is set; charger losses are excluded.")
        entry("Per full charge", "Estimated battery-side electricity cost for one full charge, with its energy in Wh.",
            "Full-charge Wh equals estimated mAh capacity times 3.87 divided by 1,000. Divide Wh by 1,000 and multiply by the saved cents-per-kWh rate, or the default 18 cents. This does not include charger losses.")
        entry("A year at this pace", "Estimated yearly cost if use continues at the recorded pace.",
            "Divide the electricity-so-far cost by elapsed days since the first phone reading and multiply by 365. Shown only after at least 1 day. Elapsed days include gaps.")
        entry("Of a fridge", "How long a typical fridge could run on the same battery energy.",
            "$energyRule Divide Wh by 1,000, divide by 1.5 kWh per day, and multiply by 24 for hours.")
        entry("In an electric car", "How far the same energy might move an electric car.",
            "$energyRule Divide Wh by 300 Wh per 1,609 meters. This is a rough energy comparison.")

        section = "Where it comes from"
        entry("Energy to make", "The same total phone battery energy used by all the source comparisons below.", energyRule)
        for ((term, rule) in listOf(
            "Coal" to "Multiply Wh by 0.5 grams per Wh.",
            "Natural gas" to "Multiply Wh by 0.21 liters per Wh.",
            "Uranium" to "Multiply Wh by 0.0000028 grams per Wh.",
            "Full sun" to "Divide Wh by a 400-watt panel to get hours, then multiply by 3,600 for seconds.",
            "Wind turbine" to "Divide Wh by a 3,000,000-watt turbine to get hours, then multiply by 3,600 for seconds.",
            "Pedaling" to "Divide Wh by a cyclist's 100 watts to get hours, then multiply by 3,600 for seconds.",
            "CO2" to "Multiply Wh by 0.37 grams per Wh at the assumed US grid average."
        )) {
            entry(term, "A rough comparison for making the same energy, assuming the stated source or average.",
                "$rule Use the total battery Wh described above. These are fixed comparison factors, not a measurement of the phone's electricity source, and exclude charger losses.")
        }

        section = "Just for fun"
        entry("Phone energy used", "Estimated energy that has gone through the phone battery since tracking started.", energyRule)
        for ((term, meaning, rule) in listOf(
            Triple("LED bulb hours", "Hours of light from a 9-watt LED bulb.", "Divide total Wh by 9 watts."),
            Triple("Old bulb hours", "Hours of light from a 60-watt incandescent bulb.", "Divide total Wh by 60 watts."),
            Triple("Cups of tea", "Cups of water heated from room temperature to boiling.", "Divide total Wh by 23 Wh per cup."),
            Triple("Slices of toast", "Slices toasted at the assumed energy per slice.", "Divide total Wh by 50 Wh per slice, based on a 1,200-watt toaster for 2.5 minutes."),
            Triple("Microwave", "Time running a 1,100-watt microwave.", "Divide total Wh by 1,100 watts for hours, then multiply by 3,600 for seconds."),
            Triple("Hair dryer", "Time running a 1,800-watt hair dryer.", "Divide total Wh by 1,800 watts for hours, then multiply by 3,600 for seconds."),
            Triple("Wi-Fi", "Time running a 6-watt home router.", "Divide total Wh by 6 watts for hours, then multiply by 3,600 for seconds."),
            Triple("AA batteries", "The equivalent energy in typical alkaline AA batteries.", "Divide total Wh by 2.7 Wh per battery."),
            Triple("Bowling-ball lift", "How high this energy could lift a 7-kilogram ball, with no energy lost.", "Convert Wh to joules by multiplying by 3,600, then divide by 7 times 9.81 for height in meters."),
            Triple("Car lift", "How high this energy could lift a 1,500-kilogram car, with no energy lost.", "Multiply Wh by 3,600 for joules, then divide by 1,500 times 9.81 for height in meters."),
            Triple("Flights of stairs", "Pure lifting work for a 75-kilogram person climbing 3-meter floors.", "Multiply Wh by 3,600 for joules, then divide by 75 times 9.81 times 3 for floors."),
            Triple("Bananas", "Equivalent food energy in bananas, without allowing for muscle efficiency.", "Divide total Wh by 122 Wh per banana, corresponding to about 105 food Calories."),
            Triple("Laptop charges", "Equivalent full charges of a 50-Wh laptop battery.", "Divide total Wh by 50 Wh per charge."),
            Triple("TV", "Time running a typical 100-watt LED TV.", "Divide total Wh by 100 watts for hours, then multiply by 3,600 for seconds.")
        )) {
            entry(term, meaning, "$rule Use the total battery Wh described above. These are fixed, rough comparisons rather than measurements of those activities.")
        }

        section = "Data coverage"
        for (window in listOf("24 hours", "7 days", "30 days")) {
            entry("$window covered", "The share of this window with readings close enough together to count as covered, for each device.",
                "Start at the later of the window's beginning or the first device reading. A step longer than 2.5 check intervals, rounded down to whole minutes and at least 1 hour, is a gap; also check the time from the last reading to now. Subtract all gap time inside the window and divide covered time by the adjusted window duration, times 100. Need at least one allowance-length window to judge.")
        }
        entry("Gap count and longest gap", "How often readings were too far apart and the longest missing span within that window.",
            "Count each over-limit step, including a stale last reading, that overlaps the window. Only its part inside the adjusted window counts as missing time. The longest gap is the largest of those clipped spans.")
        entry("Window readings and since first reading", "How many saved readings are inside the adjusted coverage window.",
            "Count timestamps from the adjusted start through now. A reading before the window can anchor coverage but is not included in this count. Since first reading appears when the device's history begins after the requested window starts.")

        section = "What Battery Lab costs"
        entry("Checks", "How many background checks the app logged over the last 24 hours.",
            "Count saved poll events in that period. This is a logged event count, not an estimate from the configured schedule.")
        entry("Milliseconds per check", "Average elapsed duration of logged checks.",
            "Average the recorded wall-clock durations in milliseconds, which include waiting time. Only poll events with a duration value contribute.")
        entry("CPU time", "Total processor time used during those checks, in seconds.",
            "Sum logged process CPU milliseconds for poll events and divide by 1,000. This is processor time, not the elapsed duration, and excludes time spent browsing the app.")
        entry("Ring wake-ups", "How many direct Bluetooth read events were logged in the last day.",
            "Count saved Bluetooth-read log events, including unsuccessful reads. This page only summarizes existing logs and never starts a Bluetooth read.")
        entry("Database size", "The most recently logged size of the phone's battery history files, in megabytes.",
            "Use the last poll event's recorded database bytes, which include the main database and its write-ahead log of pending changes. Divide by 1,048,576 to display MB.")

        section = "What each device can report"
        entry("Fields recorded", "How many displayed capability rows are marked as having stored data, out of all displayed rows.",
            "First omit wearable radio diagnostics and mark unmeasured wearable battery fields unavailable, then count recorded rows and all displayed rows. The saved inventory uses the last 14 days, with up to 400 recent phone readings for most phone fields. Recorded means available, not proven accurate.")
        entry("Field status", "Whether a battery field was recorded, needs permission, is off, is unavailable, or has unknown age.",
            "Use the saved inventory status for phone fields; most are recorded if any value exists, or unavailable after 3 detailed readings without one. Watch and Ring battery fields other than percent and charging are shown as unavailable, even in older inventories; radio diagnostics are omitted. Widget percent has unknown age because its measurement time is not exposed.")
        entry("Observed step", "The smallest saved nonzero change in a reported battery field, in its stored units.",
            "Take absolute differences between consecutive available numeric phone values, remove zeros, and keep the smallest. Saved wearable percent steps can be shown, but steps for unmeasured wearable fields are removed. This describes observed resolution, not guaranteed sensor accuracy.")
    }
}
