import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public class Util {
    static final DateTimeFormatter hhmm=DateTimeFormatter.ofPattern("hh:mm a z").withZone(ZoneId.of("UTC"));
    Parser parser;
    static Util theParser;

    private Util(TimeZone tz) {parser = new Parser(tz);}
    public static Util getParser() {
        if(theParser==null) {
            theParser=new Util(TimeZone.getTimeZone("UTC"));
        }
        return theParser;
    }

    static String format(Duration d) {
        long s=d.toSeconds();
        return s>3600 ? ( s%3600 != 0 ? String.format("%d hour%s %02d min", s / 3600,(s>7200?"s":""), (s % 3600) / 60)
                :String.format("%d hour%s", s / 3600,(s>7200?"s":"")))
                : String.format("%02d min", (s % 3600) / 60);
    }

    public Instant parseOne(String input) throws ParseException {
        List<DateGroup> dg= parser.parse(input.trim());
        if(dg.size()==1 && dg.get(0).getDates().size()==1) {
            return Instant.ofEpochMilli(dg.get(0).getDates().get(0).getTime());
        } else {
            throw new ParseException("Invalid date format "+input,0);
        }
    }

    static <T,V >Iterable<Tuple2<T,V>> getIterable(Iterable<T> iterable,V other) {
        return () -> {
            Iterator<T> it = iterable.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Tuple2<T, V> next() {
                    return Tuples.of(it.next(), other);
                }
            };
        };
    }
}
