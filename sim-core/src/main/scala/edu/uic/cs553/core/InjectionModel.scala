package edu.uic.cs553.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

case class ScheduledInjection(
    timeMs: Long,
    nodeId: Int,
    msgType: String,
    payload: String
)

sealed trait InteractiveCommand

object InteractiveCommand:
  case object NoOp extends InteractiveCommand
  case object Help extends InteractiveCommand
  case object Quit extends InteractiveCommand
  case class Inject(nodeId: Int, msgType: String, payload: String) extends InteractiveCommand
  case class Invalid(reason: String) extends InteractiveCommand

object InjectionModel:
  def parseFile(path: Path): List[ScheduledInjection] =
    Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList.zipWithIndex.flatMap {
      case (line, idx) => parseScriptLine(line, idx + 1)
    }

  def parseInteractive(line: String): InteractiveCommand =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then
      InteractiveCommand.NoOp
    else if trimmed == "help" then
      InteractiveCommand.Help
    else if trimmed == "quit" then
      InteractiveCommand.Quit
    else
      val parts = trimmed.split("\\s+", 4)
      if parts.length < 4 || parts(0) != "inject" then
        InteractiveCommand.Invalid("Expected: inject node_id msg_type payload")
      else
        parts(1).toIntOption match
          case Some(nodeId) => InteractiveCommand.Inject(nodeId, parts(2), parts(3))
          case None         => InteractiveCommand.Invalid(s"Invalid node id: '${parts(1)}'")

  private def parseScriptLine(line: String, lineNumber: Int): Option[ScheduledInjection] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then
      None
    else
      val parts = trimmed.split("\\s+", 4)
      require(parts.length >= 4, s"Invalid injection script line $lineNumber: '$line'")
      val timeMs = parts(0).toLongOption.getOrElse {
        throw new IllegalArgumentException(s"Invalid time_ms on line $lineNumber: '${parts(0)}'")
      }
      val nodeId = parts(1).toIntOption.getOrElse {
        throw new IllegalArgumentException(s"Invalid node id on line $lineNumber: '${parts(1)}'")
      }
      Some(ScheduledInjection(timeMs, nodeId, parts(2), parts(3)))
