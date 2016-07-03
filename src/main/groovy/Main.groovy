import groovy.transform.CompileStatic

import java.nio.IntBuffer
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Created by karl on 7/2/2016.
 */
@CompileStatic
class Main {
    static Pattern COMBAT_HEADER = ~/(?<date>\d+\/\d+ (\d+[:\.]){3}\d+)  (?<event>[A-Z_]+),/
    static Pattern UNIT_STATS = ~/(?<guid>\w+),"(?<name>[^-"]+-[^"]+)",(?<flags>0x\w+),(?<raidFlags>0x\w+),/
    static Pattern SPELL_INFO = ~/(?<spellId>\d+),"(?<spellName>[^"]+)",(?<spellSchool>0x\w+),/
    static Pattern HEAL_INFO = ~/(?<guid>[\w-]+),(?<u1>\w+),(?<hp>\d+),(?<hpMax>\d+),(?<stat1>\w+),(?<int>\w+),(?<stat3>\w+),(?<mp>\w+),(?<mpMax>\w+),(?<stat4>[\w\.]+),(?<stat5>[\w\.]+),(?<stat6>\w+),(?<value>\w+),(?<over>\w+),(?<absorbed>\w+),(?<crit>\w+)/

    public static void main(String[] argv) {
        Map<String,Map<String,Stats>> playerStats = [:].withDefault {[:].withDefault {new Stats()}}

        InputStream is = Main.class.getResourceAsStream('WoWCombatLog.txt')
//        FileInputStream is = new FileInputStream('E:\\Battle.net\\World of Warcraft Beta\\Logs\\WoWCombatLog-bak.txt')
        BufferedInputStream bis = new BufferedInputStream(is)
        bis.readLines().each {
            String tail = it
            Matcher header = COMBAT_HEADER.matcher(tail)
            if(!header.find()) return;
            tail = tail.substring(header.end())

            String event = header.group('event')

            if(!event.endsWith('_HEAL')) return;

            Matcher src = UNIT_STATS.matcher(tail)
            if(!src.find()) return;
            tail = tail.substring(src.end())

            Matcher dst = UNIT_STATS.matcher(tail)
            if(!dst.find()) return;
            tail = tail.substring(dst.end())

            Matcher spell = SPELL_INFO.matcher(tail)
            if(!spell.find()) return;
            tail = tail.substring(spell.end())

            Matcher heal = HEAL_INFO.matcher(tail)
            if(!heal.find()) return;
            tail = tail.substring(heal.end())

            Stats stats = playerStats.get(src.group('name')).get(spell.group('spellName'))

            int value = Integer.parseInt(heal.group('value'))
            int over = Integer.parseInt(heal.group('over'))
            int total = value + over;

            int hp = Integer.parseInt(heal.group('hp'))
            int hpMax = Integer.parseInt(heal.group('hpMax'))

            if(heal.group('crit') ==~/1/) {
                stats.crits.put(total)
                if(over > 0) {
                    stats.critgap.put(0)
                } else {
                    stats.critgap.put(hpMax - hp)
                }
            } else {
                stats.hits.put(total)
                if(over > 0) {
                    stats.hitgap.put(0)
                } else {
                    stats.hitgap.put(hpMax - hp)
                }
            }

            stats.over.put(over)
        }

        println playerStats
    }
}

@CompileStatic
class Stats {
    IntBuffer hits = IntBuffer.allocate(500)
    IntBuffer hitgap = IntBuffer.allocate(500)
    IntBuffer crits = IntBuffer.allocate(500)
    IntBuffer critgap = IntBuffer.allocate(500)
    IntBuffer over = IntBuffer.allocate(500)

    public List<Integer> asList(IntBuffer buff) {
        List<Integer> l = buff.array() as List
        return l.subList(0,buff.position());
    }

    public double ave(List<? extends Number> list) {
        double sum = (double)list.sum(0.0)
        return sum / list.size();
    }

    public double critRate() {
        return 100.0 * crits.position() / (over.position());
    }

    public String toString() {
        def h = asList hits
        def o = asList over
        def c = asList crits
        def hg = asList hitgap

        double overheal = 100.0 * (long)o.sum(0l) / ((h.sum(0l) as long) + (long)c.sum(0l))

        double critRate = critRate()

        double naiveCritLoss = 0;
        if(critRate > 0) {

        }

        return """critRate=${critRate}%
            hit=[${h.min()}-${ave(h)}-${h.max()}]
            hitgap=[${hg.min()}-${ave(hg)}-${hg.max()}]
            crit=[${c.min()}-${ave(c)}-${c.max()}]
            over=$overheal%
            """.replaceAll(/\n\s+/,' ');
    }
}