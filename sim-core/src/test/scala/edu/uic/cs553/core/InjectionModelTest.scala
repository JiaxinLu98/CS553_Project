package edu.uic.cs553.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InjectionModelTest extends AnyFunSuite with Matchers:

  test("parseFile supports comments blank lines and payloads with spaces"):
    val file = Files.createTempFile("injections", ".txt")
    Files.writeString(
      file,
      """# comment
        |
        |10 1 WORK task-17
        |250 7 PING hello from driver
        |""".stripMargin,
      StandardCharsets.UTF_8
    )

    val events = InjectionModel.parseFile(file)
    events shouldBe List(
      ScheduledInjection(10, 1, "WORK", "task-17"),
      ScheduledInjection(250, 7, "PING", "hello from driver")
    )

  test("parseInteractive recognizes inject help and quit"):
    InjectionModel.parseInteractive("help") shouldBe InteractiveCommand.Help
    InjectionModel.parseInteractive("quit") shouldBe InteractiveCommand.Quit
    InjectionModel.parseInteractive("inject 3 WORK job 99") shouldBe
      InteractiveCommand.Inject(3, "WORK", "job 99")
