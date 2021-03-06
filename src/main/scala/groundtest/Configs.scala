package groundtest

import Chisel._
import rocket._
import uncore.tilelink._
import uncore.coherence._
import uncore.agents._
import uncore.devices.NTiles
import junctions._
import scala.collection.mutable.LinkedHashSet
import scala.collection.immutable.HashMap
import cde.{Parameters, Config, Dump, Knob, CDEMatchError}
import scala.math.max
import coreplex._
import rocketchip._
import util.ConfigUtils._

/** Actual testing target Configs */

class GroundTestConfig extends Config(new WithGroundTest ++ new BaseConfig)

class ComparatorConfig extends Config(
  new WithComparator ++ new GroundTestConfig)
class ComparatorL2Config extends Config(
  new WithAtomics ++ new WithPrefetches ++
  new WithL2Cache ++ new ComparatorConfig)
class ComparatorBufferlessConfig extends Config(
  new WithBufferlessBroadcastHub ++ new ComparatorConfig)
class ComparatorStatelessConfig extends Config(
  new WithStatelessBridge ++ new ComparatorConfig)

class MemtestConfig extends Config(new WithMemtest ++ new GroundTestConfig)
class MemtestL2Config extends Config(
  new WithL2Cache ++ new MemtestConfig)
class MemtestBufferlessConfig extends Config(
  new WithBufferlessBroadcastHub ++ new MemtestConfig)
class MemtestStatelessConfig extends Config(
  new WithNGenerators(0, 1) ++ new WithStatelessBridge ++ new MemtestConfig)
// Test ALL the things
class FancyMemtestConfig extends Config(
  new WithNGenerators(1, 2) ++ new WithNCores(2) ++ new WithMemtest ++
  new WithNMemoryChannels(2) ++ new WithNBanksPerMemChannel(4) ++
  new WithSplitL2Metadata ++ new WithL2Cache ++ new GroundTestConfig)

class CacheFillTestConfig extends Config(
  new WithCacheFillTest ++ new WithPLRU ++ new WithL2Cache ++ new GroundTestConfig)

class BroadcastRegressionTestConfig extends Config(
  new WithBroadcastRegressionTest ++ new GroundTestConfig)
class BufferlessRegressionTestConfig extends Config(
  new WithBufferlessBroadcastHub ++ new BroadcastRegressionTestConfig)
class CacheRegressionTestConfig extends Config(
  new WithCacheRegressionTest ++ new WithL2Cache ++ new GroundTestConfig)

class NastiConverterTestConfig extends Config(new WithNastiConverterTest ++ new GroundTestConfig)
class FancyNastiConverterTestConfig extends Config(
  new WithNCores(2) ++ new WithNastiConverterTest ++
  new WithNMemoryChannels(2) ++ new WithNBanksPerMemChannel(4) ++
  new WithL2Cache ++ new GroundTestConfig)

class TraceGenConfig extends Config(
  new WithNCores(2) ++ new WithTraceGen ++ new GroundTestConfig)
class TraceGenBufferlessConfig extends Config(
  new WithBufferlessBroadcastHub ++ new TraceGenConfig)
class TraceGenL2Config extends Config(
  new WithNL2Ways(1) ++ new WithL2Capacity(32 * 64 / 1024) ++
  new WithL2Cache ++ new TraceGenConfig)

class MIF128BitComparatorConfig extends Config(
  new WithMIFDataBits(128) ++ new ComparatorConfig)
class MIF128BitMemtestConfig extends Config(
  new WithMIFDataBits(128) ++ new MemtestConfig)

class MIF32BitComparatorConfig extends Config(
  new WithMIFDataBits(32) ++ new ComparatorConfig)
class MIF32BitMemtestConfig extends Config(
  new WithMIFDataBits(32) ++ new MemtestConfig)

class PCIeMockupTestConfig extends Config(
  new WithPCIeMockupTest ++ new GroundTestConfig)

/* Composable Configs to set individual parameters */
class WithGroundTest extends Config(
  (pname, site, here) => pname match {
    case BuildCoreplex =>
      (c: CoreplexConfig, p: Parameters) => uncore.tilelink2.LazyModule(new GroundTestCoreplex(c)(p)).module
    case TLKey("L1toL2") => {
      val useMEI = site(NTiles) <= 1 && site(NCachedTileLinkPorts) <= 1
      TileLinkParameters(
        coherencePolicy = (
          if (useMEI) new MEICoherence(site(L2DirectoryRepresentation))
          else new MESICoherence(site(L2DirectoryRepresentation))),
        nManagers = site(NBanksPerMemoryChannel)*site(NMemoryChannels) + 1,
        nCachingClients = site(NCachedTileLinkPorts),
        nCachelessClients = site(NCoreplexExtClients) + site(NUncachedTileLinkPorts),
        maxClientXacts = ((site(DCacheKey).nMSHRs + 1) +:
                           site(GroundTestKey).map(_.maxXacts))
                             .reduce(max(_, _)),
        maxClientsPerPort = 1,
        maxManagerXacts = site(NAcquireTransactors) + 2,
        dataBeats = 8,
        dataBits = site(CacheBlockBytes)*8)
    }
    case BuildTiles => {
      val groundtest = if (site(XLen) == 64)
        DefaultTestSuites.groundtest64
      else
        DefaultTestSuites.groundtest32
      TestGeneration.addSuite(groundtest("p"))
      TestGeneration.addSuite(DefaultTestSuites.emptyBmarks)
      (0 until site(NTiles)).map { i =>
        val tileSettings = site(GroundTestKey)(i)
        (r: Bool, p: Parameters) => {
          Module(new GroundTestTile(resetSignal = r)(p.alterPartial({
            case TLId => "L1toL2"
            case TileId => i
            case NCachedTileLinkPorts => if(tileSettings.cached > 0) 1 else 0
            case NUncachedTileLinkPorts => tileSettings.uncached
          })))
        }
      }
    }
    case BuildExampleTop =>
      (p: Parameters) => uncore.tilelink2.LazyModule(new ExampleTopWithTestRAM(p))
    case FPUKey => None
    case UseAtomics => false
    case UseCompressed => false
    case RegressionTestNames => LinkedHashSet("rv64ui-p-simple")
    case _ => throw new CDEMatchError
  })

class WithComparator extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(uncached = site(ComparatorKey).targets.size)
    }
    case BuildGroundTest =>
      (p: Parameters) => Module(new ComparatorCore()(p))
    case ComparatorKey => ComparatorParameters(
      targets    = Seq("mem", "io:pbus:TL2:testram").map(name =>
                    site(GlobalAddrMap)(name).start.longValue),
      width      = 8,
      operations = 1000,
      atomics    = false, // !!! re-enable soon: site(UseAtomics),
      prefetches = site("COMPARATOR_PREFETCHES"))
    case FPUConfig => None
    case UseAtomics => false
    case "COMPARATOR_PREFETCHES" => false
    case _ => throw new CDEMatchError
  })

class WithAtomics extends Config(
  (pname, site, here) => pname match {
    case UseAtomics => true
    case _ => throw new CDEMatchError
  })

class WithPrefetches extends Config(
  (pname, site, here) => pname match {
    case "COMPARATOR_PREFETCHES" => true
    case _ => throw new CDEMatchError
  })

class WithMemtest extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(1, 1)
    }
    case GeneratorKey => GeneratorParameters(
      maxRequests = 128,
      startAddress = site(GlobalAddrMap)("mem").start)
    case BuildGroundTest =>
      (p: Parameters) => Module(new GeneratorTest()(p))
    case _ => throw new CDEMatchError
  })

class WithNGenerators(nUncached: Int, nCached: Int) extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(nUncached, nCached)
    }
    case _ => throw new CDEMatchError
  })

class WithCacheFillTest extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(uncached = 1)
    }
    case BuildGroundTest =>
      (p: Parameters) => Module(new CacheFillTest()(p))
    case _ => throw new CDEMatchError
  },
  knobValues = {
    case "L2_WAYS" => 4
    case "L2_CAPACITY_IN_KB" => 4
    case _ => throw new CDEMatchError
  })

class WithBroadcastRegressionTest extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(1, 1, maxXacts = 3)
    }
    case BuildGroundTest =>
      (p: Parameters) => Module(new RegressionTest()(p))
    case GroundTestRegressions =>
      (p: Parameters) => RegressionTests.broadcastRegressions(p)
    case _ => throw new CDEMatchError
  })

class WithCacheRegressionTest extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(1, 1, maxXacts = 5)
    }
    case BuildGroundTest =>
      (p: Parameters) => Module(new RegressionTest()(p))
    case GroundTestRegressions =>
      (p: Parameters) => RegressionTests.cacheRegressions(p)
    case _ => throw new CDEMatchError
  })

class WithNastiConverterTest extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(uncached = 1)
    }
    case GeneratorKey => GeneratorParameters(
      maxRequests = 128,
      startAddress = site(GlobalAddrMap)("mem").start)
    case BuildGroundTest =>
      (p: Parameters) => Module(new NastiConverterTest()(p))
    case _ => throw new CDEMatchError
  })

class WithTraceGen extends Config(
  topDefinitions = (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(uncached = 1, cached = 1)
    }
    case BuildGroundTest =>
      (p: Parameters) => Module(new GroundTestTraceGenerator()(p))
    case GeneratorKey => GeneratorParameters(
      maxRequests = 256,
      startAddress = 0)
    case AddressBag => {
      val nSets = 32 // L2 NSets
      val nWays = 1
      val blockOffset = site(CacheBlockOffsetBits)
      val baseAddr = site(GlobalAddrMap)("mem").start
      val nBeats = site(MIFDataBeats)
      List.tabulate(4 * nWays) { i =>
        Seq.tabulate(nBeats) { j => (j * 8) + ((i * nSets) << blockOffset) }
      }.flatten.map(addr => baseAddr + BigInt(addr))
    }
    case UseAtomics => true
    case _ => throw new CDEMatchError
  },
  knobValues = {
    case "L1D_SETS" => 16
    case "L1D_WAYS" => 1
    case _ => throw new CDEMatchError
  })

class WithPCIeMockupTest extends Config(
  (pname, site, here) => pname match {
    case NTiles => 2
    case GroundTestKey => Seq(
      GroundTestTileSettings(1, 1),
      GroundTestTileSettings(1))
    case GeneratorKey => GeneratorParameters(
      maxRequests = 128,
      startAddress = site(GlobalAddrMap)("mem").start)
    case BuildGroundTest =>
      (p: Parameters) => p(TileId) match {
        case 0 => Module(new GeneratorTest()(p))
        case 1 => Module(new NastiConverterTest()(p))
      }
    case _ => throw new CDEMatchError
  })

class WithDirectMemtest extends Config(
  (pname, site, here) => {
    val nGens = 8
    pname match {
      case GroundTestKey => Seq(GroundTestTileSettings(uncached = nGens))
      case GeneratorKey => GeneratorParameters(
        maxRequests = 1024,
        startAddress = 0)
      case BuildGroundTest =>
        (p: Parameters) => Module(new GeneratorTest()(p))
      case _ => throw new CDEMatchError
    }
  })

class WithDirectComparator extends Config(
  (pname, site, here) => pname match {
    case GroundTestKey => Seq.fill(site(NTiles)) {
      GroundTestTileSettings(uncached = site(ComparatorKey).targets.size)
    }
    case BuildGroundTest =>
      (p: Parameters) => Module(new ComparatorCore()(p))
    case ComparatorKey => ComparatorParameters(
      targets    = Seq(0L, 0x100L),
      width      = 8,
      operations = 1000,
      atomics    = false, // !!! re-enable soon: site(UseAtomics),
      prefetches = site("COMPARATOR_PREFETCHES"))
    case FPUConfig => None
    case UseAtomics => false
    case "COMPARATOR_PREFETCHES" => false
    case _ => throw new CDEMatchError
  })
