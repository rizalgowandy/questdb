package org.nfsdb.examples.append;

import com.nfsdb.journal.JournalKey;
import com.nfsdb.journal.JournalWriter;
import com.nfsdb.journal.PartitionType;
import com.nfsdb.journal.exceptions.JournalException;
import com.nfsdb.journal.factory.JournalFactory;
import org.nfsdb.examples.model.Quote;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class AppendUnordered {

    /**
     * Appends 1 million quotes with random timestamp values. Journal doesn't enforce order.
     *
     * @param args factory directory
     * @throws JournalException
     */
    public static void main(String[] args) throws JournalException {

        if (args.length != 1) {
            System.out.println("Usage: " + BasicRecordAppend.class.getName() + " <path>");
            System.exit(1);
        }

        String journalLocation = args[0];

        try (JournalFactory factory = new JournalFactory(journalLocation)) {
            try (JournalWriter<Quote> writer = factory.writer(new JournalKey<>(
                    Quote.class             // model class
                    , "quote-unordered"     // directory name where journal is stored. This is relative to factory location.
                    , PartitionType.NONE    // NONE - no partitioning
                    , false                 // order enforcement is turned off
            ))) {

                final int count = 1000000;
                final String symbols[] = {"AGK.L", "BP.L", "TLW.L", "ABF.L", "LLOY.L", "BT-A.L", "WTB.L", "RRS.L", "ADM.L", "GKN.L", "HSBA.L"};
                final Random r = new Random(System.currentTimeMillis());

                // reuse same same instance of Quote class to keep GC under control
                final Quote q = new Quote();

                long t = System.nanoTime();
                for (int i = 0; i < count; i++) {
                    // prepare object for new set of data
                    q.clear();
                    // generate some data
                    q.setSym(symbols[Math.abs(r.nextInt() % (symbols.length - 1))]);
                    q.setAsk(Math.abs(r.nextDouble()));
                    q.setBid(Math.abs(r.nextDouble()));
                    q.setAskSize(Math.abs(r.nextInt() % 10000));
                    q.setBidSize(Math.abs(r.nextInt() % 10000));
                    q.setEx("LXE");
                    q.setMode("Fast trading");
                    q.setTimestamp(r.nextLong());
                    //
                    writer.append(q);
                }

                // commit is necessary
                writer.commit();
                System.out.println("Journal size: " + writer.size());
                System.out.println("Generated " + count + " objects in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t) + "ms.");
            }
        }
    }
}
