package org.nfsdb.examples.misc;

import com.nfsdb.journal.Journal;
import com.nfsdb.journal.JournalWriter;
import com.nfsdb.journal.column.SymbolTable;
import com.nfsdb.journal.exceptions.JournalException;
import com.nfsdb.journal.factory.JournalFactory;
import org.nfsdb.examples.model.Quote;
import org.nfsdb.examples.support.QuoteGenerator;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ExistsExample {

    public static void main(String[] args) throws JournalException {
        if (args.length != 1) {
            System.out.println("Usage: " + ExistsExample.class.getName() + " <path>");
            System.exit(1);
        }
        try (JournalFactory factory = new JournalFactory(args[0])) {

            // get some data in :)
            try (JournalWriter<Quote> w = factory.writer(Quote.class)) {
                QuoteGenerator.generateQuoteData(w, 1000000);
            }

            final Set<String> values = new HashSet<String>() {{
                add("TLW.L");
                add("ABF.L");
                add("LLOY.L");
                add("TLZ.L");
                add("BT-A.L");
                add("KBR.L");
                add("WTB.L");
            }};

            try (Journal<Quote> journal = factory.reader(Quote.class)) {
                long t = System.nanoTime();
                //
                // check values against SymbolTable, if they are there they would exist in journal too.
                //
                SymbolTable tab = journal.getSymbolTable("sym");
                for (String v : values) {
                    if (tab.getQuick(v) == SymbolTable.VALUE_NOT_FOUND) {
                        System.out.println(v + ": MISSING");
                    } else {
                        System.out.println(v + ": ok");
                    }
                }
                System.out.println("Done in " + TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t) + "μs");
            }
        }
    }
}
