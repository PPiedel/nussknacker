import {ExpressionObj} from "../types"
import {Validator} from "../../Validators"
import React, {useEffect, useRef, useState} from "react"
import Cron from "react-cron-generator"
import "react-cron-generator/dist/cron-builder.css"
import Input from "../../field/Input"
import "./cronEditorStyle.styl"
import i18next from "i18next"

type CronExpression = string

type Props = {
  expressionObj: ExpressionObj,
  onValueChange: Function,
  validators: Array<Validator>,
  showValidation?: boolean,
  readOnly: boolean,
  isMarked: boolean,
  editorFocused: boolean,
  className: string,
}

const CRON_DECODE_REGEX = /T\(com\.cronutils\.parser\.CronParser\)\.parse\('(.*?)'\)/

const CRON_SPEL_EXPRESSION = (value) => `T(com.cronutils.parser.CronParser).parse('${value}')`

// we have to pass some value to <Cron/> component
// when expression is empty - this component sets some default cron value and trigger onValueChange - we don't want that
const NOT_EXISTING_CRON_EXPRESSION = "-1 -1 -1 -1 -1 -1 -1"

export default function CronEditor(props: Props) {
  const node = useRef(null)

  const {expressionObj, validators, isMarked, onValueChange, showValidation, readOnly} = props

  function encode(value) {
    return value == "" ? "" : CRON_SPEL_EXPRESSION(value)
  }

  function decode(expression: string): CronExpression {
    const cronRegexExec = CRON_DECODE_REGEX.exec(expression)
    return cronRegexExec == null ? "" : cronRegexExec[1]
  }

  const [value, setValue] = useState(decode(expressionObj.expression))
  const [open, setOpen] = useState(false)

  const handleClickOutside = e => {
    if (node.current.contains(e.target)) {
      return
    }
    setOpen(false)
  }

  useEffect(() => {
    if (open) {
      document.addEventListener("mousedown", handleClickOutside)
    } else {
      document.removeEventListener("mousedown", handleClickOutside)
    }
    return () => {
      document.removeEventListener("mousedown", handleClickOutside)
    }
  },
  [open])

  useEffect(
    () => {
      onValueChange(encode(value))
    },
    [value],
  )

  const onInputFocus = () => {
    if (!readOnly) {
      setOpen(true)
    }
  }

  return (
    <div ref={node} className={"cron-editor-container"}>
      <Input
        value={value}
        formattedValue={expressionObj.expression}
        validators={validators}
        isMarked={isMarked}
        onFocus={onInputFocus}
        showValidation={showValidation}
        readOnly={readOnly}
        inputClassName={readOnly ? "read-only" : ""}
      />
      {
        open && (
          <Cron
            onChange={(e) => {
              setValue(e)
            }}
            value={value === "" ? NOT_EXISTING_CRON_EXPRESSION : value}
            showResultText={true}
            showResultCron={false}
          />
        )}
    </div>
  )
}

CronEditor.switchableTo = (expressionObj: ExpressionObj) => CRON_DECODE_REGEX.test(expressionObj.expression) || expressionObj.expression === ""

CronEditor.switchableToHint = () => i18next.t("editors.cron..switchableToHint", "Switch to basic mode")

CronEditor.notSwitchableToHint = () => i18next.t("editors.cron.notSwitchableToHint",
  "Expression must match pattern T(com.cronutils.parser.CronParser).parse('* * * * * * *') to switch to basic mode")