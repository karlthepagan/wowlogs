import groovy.transform.CompileStatic

import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.security.SecureRandom
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

//        InputStream is = Main.class.getResourceAsStream('WoWCombatLog.txt')
        FileInputStream is = new FileInputStream('E:\\Battle.net\\World of Warcraft Beta\\Logs\\WoWCombatLog-mythic run.txt')
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
                stats.crits = put(stats.crits,total)
                if(over > 0) {
                    stats.critgap = put(stats.critgap,-over)
                } else {
                    stats.critgap = put(stats.critgap,hpMax - hp)
                }
            } else {
                stats.healHit = put(stats.healHit,total)
                if(over > 0) {
                    stats.healHitGap = put(stats.healHitGap,-over)
                } else {
                    stats.healHitGap = put(stats.healHitGap,hpMax - hp)
                }
            }

            stats.over = put(stats.over,over)
        }

        SecureRandom seeds = new SecureRandom()
        seeds.setSeed(0)

        Map<String,Map<String,List<Simulation>>> simulations = [:].withDefault {[:].withDefault {[]}}

        playerStats.each { name, spells ->
            println "$name =============="

            spells.each { spellName, Stats stats ->

                print "$spellName: "
                SecureRandom rand = new SecureRandom()
                simulations.get(name).get(spellName).add(
                        Simulation.calculate(rand, stats, seeds.nextLong()))

                println()
            }
        }

        println playerStats
    }

    public static IntBuffer put(IntBuffer buff, int value) {
        if(buff.remaining() < 1) {
            buff = IntBuffer.allocate(buff.capacity()*2)
        }

        buff.put(value)

        return buff;
    }
}

@CompileStatic
class Stats {
    IntBuffer healHitSrc = IntBuffer.allocate(500)
    IntBuffer healHit = IntBuffer.allocate(500)
    IntBuffer healHitGap = IntBuffer.allocate(500)
    FloatBuffer hittime = FloatBuffer.allocate(500)
    IntBuffer critSource = IntBuffer.allocate(500)
    IntBuffer crits = IntBuffer.allocate(500)
    IntBuffer critgap = IntBuffer.allocate(500)
    FloatBuffer crittime = FloatBuffer.allocate(500)
    IntBuffer over = IntBuffer.allocate(1000)

    public List<Integer> asList(IntBuffer buff) {
        List<Integer> l = buff.array() as List
        return l.subList(0,buff.position());
    }

    public double ave(List<? extends Number> list) {
        double sum = (double)list.sum(0.0)
        return sum / list.size();
    }

    public double ave(List<? extends Number> list, Closure closure) {
        double sum = (double)list.sum(0.0,closure)
        return sum / list.size();
    }

    public double critRate() {
        return 100.0 * crits.position() / (over.position());
    }

    private static final Closure POSITIVE = { Number n ->
        n>0?n:0
    }

    public String toString() {
        def h = asList healHit
        def o = asList over
        def c = asList crits
        def hg = asList healHitGap

        double overheal = 100.0 * (long)o.sum(0l) / ((h.sum(0l) as long) + (long)c.sum(0l))

        return """critRate=${critRate()}%
            hit=[${h.min()} .. ${ave(h)} .. ${h.max()}]
            healHitGap=[${hg.min(POSITIVE)} .. ${ave(hg,POSITIVE)} .. ${hg.max(POSITIVE)}]
            crit=[${c.min()} .. ${ave(c)} .. ${c.max()}]
            over=$overheal%
            """.replaceAll(/\n\s+/,' ');
    }
}

/**
 * simulations are achieved by **removing** stats and looking for scalar benefit and causal results
 *
 * for example:
 * * remove crit and measure the possible dps removed, overhealing removed, health removed from raid members
 * * remove haste and translate events forward in time, removing health throughout the encounter, removing damage
 */
@CompileStatic
class Simulation {
    Stats stats;
    long seed;

    double confidence;

    double removedCritOverhealingRate;
    double removedCritBenefit;
    // TODO need per-effect cast time to measure haste benefit
    // TODO translate healing events to show chain of custody until an overheal or death
    double removedHasteBenefit;
    // TODO need cause-effect spell damage to measure mastery benefit
    double removedMasteryBenefit;
    double removedVersaOverhealingRate;
    double removedVersaBenefit;

    public static Simulation calculate(Random rand, Stats stats, long seed) {
        Simulation result = new Simulation()

        if(stats.critRate() <= 0) {
            // this spell does not crit (TODO provide override)
            result.removedCritOverhealingRate = 0.0
            result.removedCritBenefit = 0.0
            result.confidence = 1.0
        } else {
            // calculate the number of existing hits which become crits per 1% crit rate gained
            // similarly, number of existing crits which become hits per 1% crit rate lost
            double converted = stats.over.position() * 0.01

            // threshold to show hypothesis of crit overheal DR
            int gainThreshold = 0
            int lossThreshold = 0
            double overhealingSum = 0
            double gapSum = 0

            result.confidence = converted / 8

            for(int i = stats.healHitGap.position(); i >= 0; i--) {
                if(stats.healHitGap.get(i) < stats.healHit.get(i)) {
                    gainThreshold++;
                    overhealingSum += stats.healHit.get(i) - stats.healHitGap.get(i)
                }
                gapSum += stats.healHitGap.get(i)
            }

            println "$gainThreshold/${stats.healHitGap.position()} possible, ave at 100% ${overhealingSum / stats.healHitGap.position()} / ${gapSum / stats.healHitGap.position()}, converted at 1% $converted"
        }

        return result;
    }
}