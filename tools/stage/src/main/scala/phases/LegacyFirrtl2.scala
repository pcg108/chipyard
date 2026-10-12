package chipyard.stage.phases

import java.io.{BufferedWriter, File, FileWriter, Writer}

import circt.stage.{ChiselStage, CIRCTTargetAnnotation, CIRCTTarget}
import firrtl.options.PhaseManager.PhaseDependency
import firrtl.options.{Shell}
import firrtl.options.Viewer.view
import firrtl.{AnnotationSeq}
import firrtl.stage.{FirrtlCircuitAnnotation}
import firrtl.options.{Phase, PhaseManager, Shell, Stage, StageError, StageMain, Dependency, StageOptions}
import chipyard.stage._

private[stage] object LegacyFirrtl2Emission {
  def emitCircuit(circuit: firrtl2.ir.Circuit, writer: Writer): Unit = {
    // The ordinary ChirrtlEmitter materializes the entire circuit as one String,
    // which exceeds the JVM array limit for sufficiently large targets.
    firrtl2.ir.Serializer.lazily(circuit).foreach(writer.write)
  }
}

final class LegacyFirrtl2Emission extends Phase with PreservesAll with HasChipyardStageUtils {
  override val prerequisites = Seq(Dependency[chipyard.stage.ChipyardChiselStage])

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val targetDir = view[StageOptions](annotations).targetDir
    val fileName = s"${view[ChipyardOptions](annotations).longName.get}.sfc.fir"

    val annos = annotations.filterNot(_.isInstanceOf[firrtl.options.TargetDirAnnotation])

    val converted = firrtl2.bridge.ChiselBridge.annosToState(annos)
    val writer = new BufferedWriter(new FileWriter(new File(targetDir, fileName)))
    try LegacyFirrtl2Emission.emitCircuit(converted.circuit, writer)
    finally writer.close()

    annotations
  }
}
