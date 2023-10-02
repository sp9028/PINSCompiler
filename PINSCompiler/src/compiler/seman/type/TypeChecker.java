/**
 * @ Author: turk
 * @ Description: Preverjanje tipov.
 */

package compiler.seman.type;

import static common.RequireNonNull.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import common.Constants;
import common.Report;
import compiler.common.Visitor;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.expr.Unary.Operator;
import compiler.parser.ast.type.*;
import compiler.seman.common.NodeDescription;
import compiler.seman.type.type.Type;
import compiler.seman.type.type.Type.Atom.Kind;

public class TypeChecker implements Visitor {
    /**
     * Opis vozlišč in njihovih definicij.
     */
    private final NodeDescription<Def> definitions;

    /**
     * Opis vozlišč, ki jim priredimo podatkovne tipe.
     */
    private NodeDescription<Type> types;
    List<String> library = new ArrayList<>(Arrays.asList("print_int","print_str","print_log","rand_int","seed"));

    public TypeChecker(NodeDescription<Def> definitions, NodeDescription<Type> types) {
        requireNonNull(definitions, types);
        this.definitions = definitions;
        this.types = types;
    }

    HashSet<TypeDef> hashSet = new HashSet<TypeDef>();

    @Override
    public void visit(Call call) {
        // Acceptaj argumente
        call.arguments.stream().forEach(argument -> argument.accept(this));

        var argumentTypes = new ArrayList<Type>();

        // Ce se tipi argumentov ujemajo jih dodaj v seznam tipov argumentov
        call.arguments.stream().forEach(argument -> {
            types.valueFor(argument).ifPresentOrElse(type -> {
                argumentTypes.add(type);
            }, () -> Report.error(call.position, "Napacen tip v argumentu!"));
        });

        // Preveri ali je funkcija del standardne knjiznice in jo ustrezno obravnavaj
        if (library.contains(call.name)) {
            switch (call.name) {
                case Constants.printStringLabel -> {
                    if (argumentTypes.size() != 1) {
                        Report.error(call.position, "Napacno stevilo argumentov!");
                    }
                    if (!argumentTypes.get(0).isStr()) {
                        Report.error(call.position, "Napacen tip argumenta!");
                    }
                    types.store(new Type.Atom(Kind.STR), call);
                    return;
                }
                case Constants.printLogLabel -> {
                    if (argumentTypes.size() != 1) {
                        Report.error(call.position, "Napacno stevilo argumentov!");
                    }
                    if (!argumentTypes.get(0).isLog()) {
                        Report.error(call.position, "Napacen tip argumenta!");
                    }
                    types.store(new Type.Atom(Kind.LOG), call);
                    return;
                }
                case Constants.randIntLabel -> {
                    if (argumentTypes.size() != 2) {
                        Report.error(call.position, "Napacno stevilo argumentov!");
                    }
                    if (!argumentTypes.get(0).isInt() || !argumentTypes.get(1).isInt()) {
                        Report.error(call.position, "Napacen tip argumenta!");
                    }
                    types.store(new Type.Atom(Kind.INT), call);
                    return;
                }
                default -> {
                    if (argumentTypes.size() != 1) {
                        Report.error(call.position, "Napacno stevilo argumentov!");
                    }
                    if (!argumentTypes.get(0).isInt()) {
                        Report.error(call.position, "Napacen tip argumenta!");
                    }
                    types.store(new Type.Atom(Kind.INT), call);
                    return;
                }
            }
        }

        // Preveri ali je funkcija definirana
        var def = definitions.valueFor(call);
        if (def.isPresent()) {
            var getDef = def.get();
            // Preveri tip funkcije
            var findType = types.valueFor(getDef);

            // Sprejmi tip funkcije, ce ga se ni
            // To je v primeru, da je tip definiran kasneje kot je funkcija definirana
            if (!findType.isPresent()) {
                getDef.accept(this);
                findType = types.valueFor(getDef);
            }

            // Ce je tip definiran,
            if (findType.isPresent()) {
                var getType = findType.get();
                var asFunc = getType.asFunction();
                if (asFunc.isPresent()) {
                    var getAsFunc = asFunc.get();
                    // Preveri ali se stevilo argumentov ujema
                    if (getAsFunc.parameters.size() != argumentTypes.size()) {
                        Report.error(call.position, "Napačno stevilo argumentov!");
                    }

                    // Preveri ali se tipi argumentov ujemajo ena po ena
                    for (int i = 0; i < getAsFunc.parameters.size(); i++) {
                        if (!(getAsFunc.parameters.get(i).equals(argumentTypes.get(i)))) {
                            Report.error(call.position, "Argument napacnega tipa!");
                        }
                    }

                    // Ustvari tip funkcije z argumenti in vrednostjo, ki jo vrne, da preveris
                    // enakost
                    var compareFunc = new Type.Function(argumentTypes, getAsFunc.returnType);
                    if (getAsFunc.equals(compareFunc)) {
                        types.store(getAsFunc.returnType, call);
                        return;
                    }
                }
            }
            Report.error(call.position, "Tip funkcije ni definiran!");
        }
    }

    @Override
    public void visit(Binary binary) {
        binary.left.accept(this);
        binary.right.accept(this);

        // Pridobi tipa levega in desnega izraza
        var leftType = types.valueFor(binary.left);
        var rightType = types.valueFor(binary.right);

        // Preveri ali sta oba tipa definirana
        if (leftType.isPresent() && rightType.isPresent()) {
            // Ce je operator and ali or, preveri ali sta oba tipa logična
            if (binary.operator.isAndOr()) {
                if (leftType.get().isLog() && rightType.get().isLog()) {
                    types.store(new Type.Atom(Kind.LOG), binary);
                    return;
                } else {
                    Report.error(binary.position, "Napaka pri logičnem izrazu!");
                }
                // Ce je operator aritmeticen preveri ali sta oba tipa cela stevila
            } else if (binary.operator.isArithmetic()) {
                if (leftType.get().isInt() && rightType.get().isInt()) {
                    types.store(new Type.Atom(Kind.INT), binary);
                    return;
                } else {
                    Report.error(binary.position, "Napaka pri aritmetičnem izrazu!");
                }
                // Ce je operator primerjalni preveri ali sta oba tipa cela stevila ali logična
            } else if (binary.operator.isComparison()) {
                if ((leftType.get().isInt() && rightType.get().isInt()) || (leftType.get().isLog()
                        && rightType.get().isLog())) {
                    types.store(new Type.Atom(Kind.LOG), binary);
                    return;
                } else {
                    Report.error(binary.position, "Napaka pri primerjalnem izrazu!");
                }
                // Ce je operator arr preveri da je levi tip tabela in desni tip celo stevilo
            } else if (binary.operator.equals(Binary.Operator.ARR)) {
                if (leftType.get().isArray() && rightType.get().isInt()) {
                    var asArray = leftType.get().asArray();
                    types.store(asArray.get().type, binary);
                    return;
                } else
                    Report.error(binary.position, "Napaka pri izrazu s tabelami!");
                // Ce je operator prirejanje preveri, da se levi in desni tip ujemata in da sta
                // oba logična, cela stevila ali nizi
            } else if (binary.operator.equals(Binary.Operator.ASSIGN)) {
                if (leftType.get().equals(rightType.get()) && ((rightType.get().isLog()) || rightType.get().isInt()
                        || rightType.get().isStr())) {
                    types.store(leftType.get(), binary);
                    return;
                } else
                    Report.error(binary.position, "Nedovoljeno prirejanje!");
            }
        }
    }

    @Override
    public void visit(Block block) {
        // Sprejmi vse izraze v bloku
        block.expressions.stream().forEach(expression -> {
            expression.accept(this);
        });
        // Zadnji izraz doloca tip bloka
        var lastExpression = block.expressions.get(block.expressions.size() - 1);
        // Preveri tip zadnjega izraza
        types.valueFor(lastExpression).ifPresentOrElse(type -> types.store(type, block), () -> {
            Report.error(block.position, "Neveljaven tip zadnjega izraza!");
        });
    }

    @Override
    public void visit(For forLoop) {
        // Sprejmi vse izraze v for zanki
        forLoop.counter.accept(this);
        forLoop.low.accept(this);
        forLoop.high.accept(this);
        forLoop.body.accept(this);
        forLoop.step.accept(this);

        // Preveri tipa stevca, spodnje meje, zgornje meje in inkrementa
        var counterType = types.valueFor(forLoop.counter);
        counterType.ifPresentOrElse(type -> {
            if (!type.isInt())
                Report.error(forLoop.counter.position, "Neveljaven tip števca v for zanki!");
        }, () -> Report.error(forLoop.counter.position, "Napaka v števcu zanke!"));
        var lowType = types.valueFor(forLoop.low);
        lowType.ifPresentOrElse(type -> {
            if (!type.isInt())
                Report.error(forLoop.low.position, "Neveljaven tip spodnje meje v for zanki!");
        }, () -> Report.error(forLoop.low.position, "Napaka v spodnji meji zanke!"));
        var highType = types.valueFor(forLoop.high);
        highType.ifPresentOrElse(type -> {
            if (!type.isInt())
                Report.error(forLoop.high.position, "Neveljaven tip zgornje meje v for zanki!");
        }, () -> Report.error(forLoop.high.position, "Napaka v zgornji meji zanke!"));
        var stepType = types.valueFor(forLoop.step);
        stepType.ifPresentOrElse(type -> {
            if (!type.isInt())
                Report.error(forLoop.step.position, "Neveljaven tip inkrementa v for zanki!");
        }, () -> Report.error(forLoop.step.position, "Napaka v inkrementu zanke!"));

        types.store(new Type.Atom(Kind.VOID), forLoop);
    }

    @Override
    public void visit(Name name) {
        types.store(types.valueFor(definitions.valueFor(name).get()).get(), name);
    }

    @Override
    public void visit(IfThenElse ifThenElse) {
        // Sprejmi vse izraze v pogojni izjavi
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(type -> type.accept(this));

        // Preveri tip pogojne izjave
        var conditionType = types.valueFor(ifThenElse.condition);
        conditionType.ifPresentOrElse(type -> {
            if (!type.isLog())
                Report.error(ifThenElse.condition.position, "Neveljaven tip pogojne izjave!");
        }, () -> Report.error(ifThenElse.condition.position, "Napaka v pogojni izjavi!"));

        types.store(new Type.Atom(Kind.VOID), ifThenElse);
    }

    @Override
    public void visit(Literal literal) {
        switch (literal.type) {
            case INT:
                types.store(new Type.Atom(Kind.INT), literal);
                break;
            case LOG:
                types.store(new Type.Atom(Kind.LOG), literal);
                break;
            case STR:
                types.store(new Type.Atom(Kind.STR), literal);
                break;
            default:
                Report.error(literal.position, "Neznan tip!");
        }
    }

    @Override
    public void visit(Unary unary) {
        unary.expr.accept(this);
        var exprType = types.valueFor(unary.expr);
        // Preveri tip unarnega izraza
        exprType.ifPresentOrElse(type -> {
            // Ce je unarni operator negacija, mora biti tip logičen
            if (type.isLog() && unary.operator.equals(Operator.NOT)) {
                types.store(new Type.Atom(Kind.LOG), unary);
                return;
            }
            // Ce je unarni operator minus ali plus, mora biti tip celo število
            if (type.isInt() && (unary.operator.equals(Operator.SUB) || unary.operator.equals(Operator.ADD))) {
                types.store(new Type.Atom(Kind.INT), unary);
                return;
            } else {
                Report.error(unary.position, "Napaka v unarnem izrazu!");
            }
        }, () -> Report.error(unary.position, "Napaka v izrazu!"));
    }

    @Override
    public void visit(While whileLoop) {
        whileLoop.condition.accept(this);
        whileLoop.body.accept(this);

        // Preveri tip pogoja
        var conditionType = types.valueFor(whileLoop.condition);
        conditionType.ifPresentOrElse(type -> {
            if (!type.isLog())
                Report.error(whileLoop.condition.position, "Neveljaven tip pogoja v while zanki!");
        }, () -> Report.error(whileLoop.condition.position, "Napaka v pogoju zanke!"));

        types.store(new Type.Atom(Kind.VOID), whileLoop);
    }

    @Override
    public void visit(Where where) {
        // Sprejmi definicije in izraze
        where.defs.accept(this);
        where.expr.accept(this);

        // Preveri tip izraza
        types.valueFor(where.expr).ifPresentOrElse(type -> types.store(type, where), () -> {
            Report.error(where.position, "Neveljaven tip izraza!");
        });
    }

    @Override
    public void visit(Defs defs) {
        defs.definitions.stream().forEach(definition -> {
            definition.accept(this);
        });
    }

    @Override
    public void visit(FunDef funDef) {
        // Sprejmi parametre
        funDef.parameters.stream().forEach(param -> param.accept(this));
        // Sprejmi funkcijski tip
        funDef.type.accept(this);

        var parameterTypes = new ArrayList<Type>();
        // Preveri tip parametrov
        funDef.parameters.stream().forEach(param -> {
            types.valueFor(param).ifPresentOrElse(type -> parameterTypes.add(type),
                    () -> Report.error(funDef.position, "Napaka v tipu parametra!"));
        });

        // Preveri tip funkcije
        types.valueFor(funDef.type).ifPresentOrElse(
                type -> types.store(new Type.Function(parameterTypes, type), funDef),
                () -> Report.error(funDef.type.position, "Napaka v tipih funkcije"));

        // Preveri tip telesa funkcije
        funDef.body.accept(this);
        types.valueFor(funDef.body).ifPresentOrElse(funBodyType -> {
            // Preveri tip telesa funkcije
            types.valueFor(funDef.type).ifPresent(funType -> {
                // Preveri ali se tipa ujemata
                if (types.valueFor(funDef.type).get().equals(funBodyType)) {
                    types.store(new Type.Function(parameterTypes, funType), funDef);
                    return;
                } else
                    Report.error(funDef.type.position, "Neveljaven return type v funkciji!");
            });
        }, () -> Report.error(funDef.body.position, "Napaka v body funkcije!"));
    }

    @Override
    public void visit(TypeDef typeDef) {
        // Preverjanje ciklov... Ce smo tekom preverjanja ze obiskali ta tip, potem je
        // cikel
        // V smislu typ a = b; typ b = a;
        if (hashSet.contains(typeDef))
            Report.error("Zaznan cikel!");
        hashSet.add(typeDef);
        // Sprejmi tip tipa (splezaj do atomarne definicije)
        typeDef.type.accept(this);

        // Preveri ali je tip definiran
        types.valueFor(typeDef.type).ifPresentOrElse(type -> {
            types.store(type, typeDef);
        }, () -> Report.error(typeDef.position, "Tip " + typeDef.name + " ni definiran!"));
    }

    @Override
    public void visit(VarDef varDef) {
        // Sprejmi tip spremenljivke
        varDef.type.accept(this);
        // Preveri ce je tip definiran
        types.valueFor(varDef.type).ifPresentOrElse(type -> {
            types.store(type, varDef);
        }, () -> Report.error(varDef.position,
                "Tip spremenljivke " + varDef.name + " ni definiran!"));
    }

    @Override
    public void visit(Parameter parameter) {
        // Sprejmi tip parametra
        parameter.type.accept(this);
        // Preveri ali je tip definiran
        types.valueFor(parameter.type).ifPresentOrElse(type -> {
            types.store(type, parameter);
        }, () -> Report.error(parameter.position,
                "Tip parametra " + parameter.name + " ni definiran!"));
    }

    @Override
    public void visit(Array array) {
        array.type.accept(this);

        // Preveri ali je tip seznama definiran
        types.valueFor(array.type).ifPresentOrElse(type -> {
            // Ce je tip atomaren, potem je tip seznama atomaren
            type.asAtom().ifPresent(type2 -> {
                types.store(new Type.Array(array.size, type2), array);
                return;
            });

            // Ce je tip seznam, potem je tip seznama seznam (multi-dimenzionalni seznam)
            type.asArray().ifPresent(type3 -> {
                types.store(new Type.Array(array.size, type3), array);
                return;
            });
        }, () -> Report.error(array.position, "Tip seznama ni definiran!"));
    }

    @Override
    public void visit(Atom atom) {
        hashSet.clear();
        switch (atom.type) {
            case INT -> types.store(new Type.Atom(Kind.INT), atom);
            case LOG -> types.store(new Type.Atom(Kind.LOG), atom);
            case STR -> types.store(new Type.Atom(Kind.STR), atom);
            default -> Report.error(atom.position, "Nepoznan tip atomarnega izraza!");
        }
    }

    @Override
    public void visit(TypeName name) {
        var def = definitions.valueFor(name);
        def.ifPresentOrElse(definition -> {
            var findType = types.valueFor(definition);

            // Sprejmi tip, ce ga se ni
            // To je v primeru, da je tip definiran kasneje kot je uporabljen
            if (!findType.isPresent()) {
                definition.accept(this);
                findType = types.valueFor(definition);
            }
            findType.ifPresentOrElse(type -> {
                var asAtom = type.asAtom();
                asAtom.ifPresent(atom -> {
                    types.store(atom, name);
                    return;
                });

                var asArray = type.asArray();
                asArray.ifPresent(array -> {
                    types.store(array, name);
                    return;
                });
            }, () -> Report.error(name.position, "Nedefiniran tip!"));
        }, () -> Report.error(name.position, "Nedefiniran tip!"));
    }
}