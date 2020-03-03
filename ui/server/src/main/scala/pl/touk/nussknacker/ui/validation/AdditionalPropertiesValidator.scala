package pl.touk.nussknacker.ui.validation

import pl.touk.nussknacker.engine.ProcessingTypeData.ProcessingType
import pl.touk.nussknacker.engine.api.context.PartSubGraphCompilationError
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.NodeId
import pl.touk.nussknacker.engine.api.definition.ParameterValidator
import pl.touk.nussknacker.engine.api.process.ParameterConfig
import pl.touk.nussknacker.restmodel.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.restmodel.validation.ValidationResults.{NodeValidationError, ValidationResult}

class AdditionalPropertiesValidator(additionalFieldsConfig: Map[ProcessingType, Map[String, ParameterConfig]],
                                    errorType: String) {

  import cats.data.{ValidatedNel, _}
  import cats.implicits._

  private type Validation[T] = ValidatedNel[NodeValidationError, T]
  private type PropertiesConfig = Map[String, ParameterConfig]

  def validate(process: DisplayableProcess): ValidationResult = {
    val additionalFieldsConfigForProcessingType: Option[Map[String, ParameterConfig]] = additionalFieldsConfig.get(process.processingType)
    additionalFieldsConfigForProcessingType match {
      case None =>
        ValidationResult.errors(Map(), List(), List(PrettyValidationErrors.noValidatorKnown(process.processingType)))
      case Some(config) =>
        val additionalFields = process
          .metaData
          .additionalFields
          .map(field => field.properties)
          .getOrElse(Map.empty)
          .toList

        val validatorsByFieldName: Map[String, List[ParameterValidator]] = additionalFieldsConfigForProcessingType.get
          .map(additionalFieldConfig => additionalFieldConfig._1 -> additionalFieldConfig._2.validators.getOrElse(List.empty))

        val fieldWithValidators = for {
          field <- additionalFields
          validator <- validatorsByFieldName.getOrElse(field._1, List.empty)
        } yield (field, validator)

        val validationResults = fieldWithValidators.map {
          case (field, validator) => validator.isValid(field._1, field._2)(NodeId("properties")).toValidatedNel
        }


        val value: ValidatedNel[PartSubGraphCompilationError, Unit.type] = validationResults.sequence.map { _ => Unit }

        val processPropertiesErrors = value match {
          case Validated.Invalid(e) => e.map(error => PrettyValidationErrors.formatErrorMessage(error)).toList
        }

        ValidationResult.errors(Map(), processPropertiesErrors, List())
    }
  }
}
