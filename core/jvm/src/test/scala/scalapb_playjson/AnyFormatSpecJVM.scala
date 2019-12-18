package scalapb_playjson

import com.google.protobuf.any.{Any => PBAny}
import jsontest.anytests.AnyTest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class AnyFormatSpecJVM extends AnyFlatSpec with Matchers with JavaAssertions {
  override def registeredCompanions = Seq(AnyTest)

  "Any" should "be serialized the same as in Java (and parsed back to original)" in {
    val RawExample = AnyTest("test")
    val AnyExample = PBAny.pack(RawExample)
    assertJsonIsSameAsJava(AnyExample)
  }
}
