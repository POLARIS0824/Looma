# Shelf refresh incident, Monday import

The shared tablet catalog showed a new field report until somebody refreshed the shelf. The item stayed on disk, and opening it by path showed the last paragraph. The catalog card returned to Friday's title and vanished from the procedure search. Nobody had renamed the underlying file during the test. At first we treated the incident as a query-cache problem because a second refresh brought the card back.

## Timeline

| Local time | Actor | Observation |
| --- | --- | --- |
| 10:18:05 | Shelf reader | Opens a directory walk at revision 471 |
| 10:18:07 | Field editor | Persists a note and publishes its new search row |
| 10:18:08 | Shelf reader | Completes its scan with the earlier copy |
| 10:18:09 | QA | Search shows Friday's title |
| 10:18:15 | QA | A later refresh restores Monday's title |

The directory walker reads one page of files at a time. A held page can contain a note that the editor subsequently changes. Both code paths produce valid rows, and ordering by arrival favors the older walk because it has more work to finish. We reproduced the ordering by pausing the walker after reading the target file and resuming it after the editor received its success event.

### Test variations

- A write to a different shelf should leave this shelf's refresh eligible to publish.
- A cancelled refresh should release its temporary rows before the UI tries again.
- Two updates to the same file inside one filesystem clock tick should still preserve the last accepted edit.
- A disappearing mount should produce a banner and retain the last visible index.

> The field report never disappeared from the directory. We were reading two generations of its catalog metadata.

## Publication

We left the disk read outside the publication transaction so an import of five thousand files would not hold up an edit. The transaction rechecks the shelf generation just before it replaces rows. Computation for an outdated generation is dropped and queued for one fresh pass. When the second attempt also loses to a writer, the catalog stays visible and marks that refresh as deferred.

```text
tap refresh -> snapshot 471 -> read page -> wait
save edit   -> write file   -> publish 472
resume scan -> close page   -> compare 471 against 472
```

## Missing files

The deletion case had a separate trigger. The external drive returned an I/O error after its first directory. The same run had read plenty of valid file records, but their absence from the tail of the page list told us nothing about which files were still on the drive. An interrupted walk keeps existing rows for paths it did not manage to visit.

## Sign-off

Support can identify the failure by checking whether the saved file opens directly while its refreshed catalog card still shows old metadata. The successful verification must include a deliberate pause between reading and publishing. At publication, a refresh may replace search rows only if its starting generation still matches the shelf's current generation.
