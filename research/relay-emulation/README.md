# Relay emulator (for the article's evaluation)

Runs the **real** Call Ranya relay and crypto code (not a re-implementation) on a desktop JVM: each "phone" is a Java process
with its own identity key, SQLite stores and TCP server on its own loopback address (127.0.0.1, 127.0.0.2, …).
Android-only pieces (Context, SQLite, Base64, notifications) are replaced by small stand-ins in `shim/` and `stubs/`.

    ./build.sh          # downloads the compiler + android.jar + org.json + sqlite-jdbc once, then compiles
    python3 run.py      # 3-phone scenario: A → (B carries) → C, receipts back to A, SOS and announcement
    java -cp testc:...  HandshakeTestKt   # handshake tests (printed by build.sh)

Each node accepts commands on stdin (`contact`, `send`, `checkin`, `sos`, `ann`, `sync <ip>`, `maintain`, `csv`), so larger
experiments (50–200 nodes with island/mobility schedules) can be scripted in Python by extending `run.py`.
The research CSV of each node is written to `/tmp/dtn/<name>/media/`.

Expected output of `run.py` (abridged):

    A> send 33333333 …            msgs: OUT->33333333 [text] … status=4        (in the relay)
    B> sync 127.0.0.1             held=4 …                                     (B carries 4 bundles)
    C> sync 127.0.0.2             toMe=2  msgs: IN<-11111111 [text] …          (delivered, decrypted)
    B> sync 127.0.0.3             held=2                                        (receipts purged B's copies)
    A> sync 127.0.0.2             myDelivered=2  … status=5                     (A sees "delivered via relay")
    C> sync 127.0.0.1             toMe=2                                        (no duplicates)
