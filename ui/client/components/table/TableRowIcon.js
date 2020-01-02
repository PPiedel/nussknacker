import {Glyphicon} from "react-bootstrap"
import React from "react"

export default function TableRowIcon(props) {

  const {glyph, title, onClick, onKeyPress} = props

  const enterKey = "Enter"

  return <Glyphicon glyph={glyph}
                    title={title}
                    onClick={onClick}
                    className={"processes-table-row-icon"}
                    tabIndex={0}
                    onKeyPress={event => {
                      if (event.key === enterKey) {
                        onClick()
                      }
                    }}
  />
}