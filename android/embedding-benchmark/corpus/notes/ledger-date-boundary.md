# Date labels on harvested rain gauges

The botanic station receives bucket counts from three radios and uses them to build hourly plots. Each transmitter labels a count with the instant at which it sent the packet. A telemetry collector may receive the packet several minutes later. Staff compare a daily plot with a paper gauge check written in local time. This spring the Thursday sheet sometimes showed a water level that staff had logged shortly before midnight on Wednesday. The apparent offset lasted only for records just around the displayed day boundary.

## Collection

We keep the packet time as an absolute instant. The collector also stores the upstream sender and the receiving mast so a field worker can identify repeats. When the connection drops, the radio can resend the last two pages. Collectors first eliminate repeats by sender and sequence. They sort retained records using the packet time. A direct handwritten correction retains the employee account and notice of whether a tip count was corrected.

| Time field | Meaning | Used for |
| --- | --- | --- |
| sent | Station clock at measurement | Chronology |
| received | Collector clock at intake | Relay service |
| shown | Civil clock in selected station zone | Reading the plot |
| reportDate | Calendar date in selected station zone | Daily grouping |

### A shift across a clock change

The clocks on the east and west masts report the same instant if calibrated correctly. The shifts on their maintenance slips can still have different calendar dates when the site zone changes during travel. Morning supervisors use the station's configured time zone. The collector machine uses its own fixed configuration independently. A correction authored on a travelling tablet inherits the station zone, even if its calendar app is showing the hotel zone.

```text
groupKey = sent.atZone(stationZone).toLocalDate()
```

This path uses the configured zone rules to determine day membership. The CSV keeps the instant and the selected local calendar date so someone can match the exported row to a paper slip.

## Power recovery

The rain collector has a battery and the radios can hold one day's batches. If an entire station goes dark, staff should keep the paper sheets until each missing block has uploaded. A battery replacement can cause duplicate sequence values. Pair the burst beginning after the restart marker with the new radio session before eliminating repeated records.

### Review cases

| Scenario | Reading to verify |
| --- | --- |
| Sample arrives after midnight | Count belongs with the sample's measurement day |
| Clocks fall back | Two equal-looking local clock times remain two separate instants |
| Spring clock jumps | Station may report a calendar day with 23 hours |
| Paper sheet reopened a week later | Station zone still decides the day label |

The earlier release constructed every daily window by taking the previous start and adding twenty-four hours. This misplaced one hour around a clock change. Derive both endpoints from successive local midnights because a station day can last 23 or 25 hours.
