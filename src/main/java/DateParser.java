import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;

public class DateParser  extends Parser{
    static DateParser parser;
    private DateParser(TimeZone tz) {super(tz);}
    public static DateParser getParser() {
        if(parser==null) {
            parser=new DateParser(TimeZone.getTimeZone("UTC"));
        }
        return parser;
    }
    Instant parseOne(String input) throws ParseException {
        List<DateGroup> dg= DateParser.getParser().parse(input.trim());
        if(dg.size()==1 && dg.get(0).getDates().size()==1) {
            return Instant.ofEpochMilli(dg.get(0).getDates().get(0).getTime());
        } else {
            throw new ParseException("Invalid date format "+input,0);
        }
    }
}
