import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.MessageChannel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChannelAndCommands {
    static void initAll() {
        register(new ShowdownCommands());
        register(new AnalysisCenterCommands());
        register(new TrapCommands());
        register(new ReservoirRaidCommands());
    }
    static private final HashSet<ChannelAndCommands> channelset=new HashSet<>();
    private final String topic,name;

    private ArrayList<Command> theCommands=new ArrayList<>();
    private ArrayList<NCommand<?>> theNCommands=new ArrayList<>();
    static public void register(ChannelAndCommands cac) { channelset.add(cac);}
    ChannelAndCommands(String n,String t) { name=n;topic=t; }
    public void register(Command cmd) { theCommands.add(cmd);}
    public void register(NCommand<?> cmd) { theNCommands.add(cmd);}
    public void init() {
        if(theCommands.size() >0) Command.registerCmds(name,theCommands);
        else NCommand.registerCmds(name,theNCommands);
    }
    static public Set<ChannelAndCommands> getAllChannels() {return channelset;}

    public MessageChannel getChannel() {
        return theChannel;
    }
    public void setChannel(MessageChannel theChannel) {
        this.theChannel = theChannel;
    }
    private MessageChannel theChannel;
    String getDefaulName() {
        return name;
    }
    public boolean equals(Object o) {
        return o instanceof ChannelAndCommands && name.equals(((ChannelAndCommands)o).name);
    }

    public String getTopic() {
        return topic;
    }
}
