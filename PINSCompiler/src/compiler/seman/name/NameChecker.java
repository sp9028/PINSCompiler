/**
 * @ Author: turk
 * @ Description: Preverjanje in razreševanje imen.
 */

package compiler.seman.name;

import static common.RequireNonNull.requireNonNull;

import common.Constants;
import common.Report;
import compiler.common.Visitor;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.*;
import compiler.seman.common.NodeDescription;
import compiler.seman.name.env.SymbolTable;
import compiler.seman.name.env.SymbolTable.DefinitionAlreadyExistsException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NameChecker implements Visitor {
    /**
     * Opis vozlišč, ki jih povežemo z njihovimi
     * definicijami.
     */
    private NodeDescription<Def> definitions;

    /**
     * Simbolna tabela.
     */
    private SymbolTable symbolTable;
    List<String> library = new ArrayList<>(Arrays.asList("print_int","print_str","print_log","rand_int","seed"));
    /**
     * Ustvari nov razreševalnik imen.
     */
    public NameChecker(
            NodeDescription<Def> definitions,
            SymbolTable symbolTable) {
        requireNonNull(definitions, symbolTable);
        this.definitions = definitions;
        this.symbolTable = symbolTable;
    }

    @Override
    public void visit(Call call) {
        // Preveri ali je funkcija definirana
        symbolTable.definitionFor(call.name).ifPresentOrElse(value -> {
            // Ce je definirana, preveri ali je funkcija
            if (!(value instanceof FunDef))
                Report.error(call.name + " ni funkcija!");
            definitions.store(value, call);
        }, () -> {
            // Ce ni definirana, preveri ali je v knjiznici
            if (library.contains(call.name))
                call.arguments.forEach(argument -> argument.accept(this));
            else
                Report.error(call.name + " ni definirana funkcija!");
        });
        // Acceptaj argumente
        call.arguments.forEach(argument -> argument.accept(this));
    }

    @Override
    public void visit(Binary binary) {
        binary.left.accept(this);
        binary.right.accept(this);
    }

    @Override
    public void visit(Block block) {
        block.expressions.forEach(definition -> definition.accept(this));
    }

    @Override
    public void visit(For forLoop) {
        forLoop.counter.accept(this);
        forLoop.low.accept(this);
        forLoop.high.accept(this);
        forLoop.step.accept(this);
        forLoop.body.accept(this);
    }

    @Override
    public void visit(Name name) {
        // Preveri ali je ime definirano
        symbolTable.definitionFor(name.name).ifPresentOrElse(value -> {
            // Ce je definirano, preveri ali je spremenljivka
            if (value instanceof FunDef)
                Report.error(name.position + " " + name.name + " je funkcija, ne spremenljivka!");
            else if (value instanceof TypeDef)
                Report.error(name.position + " " + name.name + " je tip, ne spremenljivka!");
            else
                definitions.store(value, name);
        }, () -> Report.error(name.position + " Nedefinirana spremenljivka " + name.name));
    }

    @Override
    public void visit(IfThenElse ifThenElse) {
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(expression -> expression.accept(this));
    }

    @Override
    public void visit(Literal literal) {
    }

    @Override
    public void visit(Unary unary) {
        unary.expr.accept(this);
    }

    @Override
    public void visit(While whileLoop) {
        whileLoop.condition.accept(this);
        whileLoop.body.accept(this);
    }

    @Override
    public void visit(Where where) {
        symbolTable.inNewScope(() -> {
            where.defs.accept(this);
            where.expr.accept(this);
        });
    }

    @Override
    public void visit(Defs defs) {
        defs.definitions.forEach(def -> {
            try {
                symbolTable.insert(def);
            } catch (DefinitionAlreadyExistsException e) {
                // Ce definicija ze obstaja v simbolni tabeli, javi napako
                Report.error("Definition already exists! " + def.name + def.position);
            }
        });

        defs.definitions.forEach(def -> def.accept(this));
    }

    @Override
    public void visit(FunDef funDef) {
        // Sprejmi tip funkcije in tip parametrov
        funDef.type.accept(this);
        funDef.parameters.forEach(parameter -> parameter.type.accept(this));
        // Sprejmi telo funkcije in parametre v novem scope-u
        symbolTable.inNewScope(() -> {
            funDef.parameters.forEach(parameter -> parameter.accept(this));
            funDef.body.accept(this);
        });
    }

    @Override
    public void visit(TypeDef typeDef) {
        typeDef.type.accept(this);
    }

    @Override
    public void visit(VarDef varDef) {
        varDef.type.accept(this);
    }

    @Override
    public void visit(Parameter parameter) {
        // Preveri ali je parameter ze definiran
        try {
            symbolTable.insert(parameter);
        } catch (DefinitionAlreadyExistsException e) {
            Report.error("Error in NameChecker.visit(Parameter parameter)");
        }
    }

    @Override
    public void visit(Array array) {
        array.type.accept(this);
    }

    @Override
    public void visit(Atom atom) {
    }

    @Override
    public void visit(TypeName name) {
        // Preveri ali je tip definiran
        symbolTable.definitionFor(name.identifier).ifPresentOrElse(value -> {
                    // Ce je definiran, preveri ali je tip
                    if (value instanceof VarDef) {
                        Report.error(name.position + " " + name.identifier + " je spremenljvka, ne tip!");
                    } else if (value instanceof FunDef) {
                        Report.error(name.position + " " + name.identifier + " je funkcija, ne tip!");
                    } else if (value instanceof Parameter) {
                        Report.error(name.position + " " + name.identifier + " je parameter, ne tip!");
                    } else
                        definitions.store(value, name);
                },
                () -> Report.error(name.position + " Nedefiniran tip " + name.identifier));
    }
}