package pl.touk.nussknacker.engine.sql

import pl.touk.nussknacker.engine.api.typed.{ClazzRef, TypedMap}
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedClass, TypedObjectTypingResult, TypingResult}

import scala.reflect.ClassTag

object TypedList {
  def apply[T](implicit classTag: ClassTag[T]): TypingResult =
    Typed(ClazzRef(classOf[List[T]], List(ClazzRef[T])))

  def apply(fields: Map[String, TypingResult]): TypingResult =
    TypedClass(classOf[java.util.List[_]], List(TypedObjectTypingResult(fields)))

}
